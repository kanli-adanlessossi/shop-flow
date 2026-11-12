<#
.SYNOPSIS
  Runs place-orders.js against order-platform with virtual threads off and on, and prints the results.

.DESCRIPTION
  For every run it starts from a fresh PostgreSQL (docker compose down -v), starts the app with
  spring.threads.virtual.enabled set to the mode under test, confirms from a thread dump which kind
  of threads serve requests, runs a short unrecorded warm-up, then the measured run. k6 runs in
  Docker. WireMock stands in for inventory-service on :8081 (answering normally).

  Summaries are written to load-tests/results/<tag>.json. Needs Docker, and Java and Maven wrapper
  as for a normal local start. Takes about 4 minutes per run.

.PARAMETER Modes     Order of the runs, e.g. off,on,off,on (default). Each entry is 'off' or 'on'.
.PARAMETER Rate      Orders per second in the measured run (default 200).
.PARAMETER Duration  Length of the measured run (default 2m).
#>
param(
  [string[]]$Modes = @('off', 'on', 'off', 'on'),
  [int]$Rate = 200,
  [string]$Duration = '2m'
)

# docker writes progress to stderr; with 'Stop' that would abort the script.
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path "$PSScriptRoot\..").Path
$app = Join-Path $root 'order-platform'
$results = Join-Path $PSScriptRoot 'results'
New-Item -ItemType Directory -Force $results | Out-Null
Get-ChildItem $results -Filter 'vt-*' | Remove-Item -Force   # only this invocation's runs are reported
# `powershell -File script.ps1 -Modes off,on` delivers one string "off,on", so split it.
$Modes = @($Modes | ForEach-Object { $_ -split ',' } | Where-Object { $_ })

function Stop-App {
  $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
  Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
    Where-Object { $_.CommandLine -match 'order-platform' -and $_.CommandLine -match 'spring-boot' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 3
}

function Invoke-K6($tag, $rate, $duration, $export) {
  $k6 = @('run', '--quiet')
  if ($export) { $k6 += "--summary-export=/results/$tag.json" }
  $null = & docker run --rm -e "BASE_URL=http://host.docker.internal:8080" -e "RATE=$rate" -e "DURATION=$duration" `
    -v "$PSScriptRoot`:/scripts:ro" -v "$results`:/results" grafana/k6 @k6 /scripts/place-orders.js 2>&1
  # k6 exits non-zero when a threshold is crossed; that is a result, not a failure of this script.
}

docker compose -f "$root\deploy\wiremock\compose.yaml" up -d | Out-Null
Start-Sleep -Seconds 5
& "$root\deploy\wiremock\wiremock.ps1" inventory ok | Out-Null

$n = @{ on = 0; off = 0 }
foreach ($mode in $Modes) {
  $n[$mode]++
  $tag = "vt-$mode-$($n[$mode])"
  $flag = if ($mode -eq 'on') { 'true' } else { 'false' }
  "== $tag (spring.threads.virtual.enabled=$flag)"

  Stop-App
  Push-Location $app
  docker compose down -v 2>&1 | Out-Null
  $log = Join-Path $results "$tag-app.log"
  $proc = Start-Process -FilePath "$app\mvnw.cmd" -WorkingDirectory $app -WindowStyle Hidden -PassThru `
    -ArgumentList @('spring-boot:run', "-Dspring-boot.run.arguments=--spring.threads.virtual.enabled=$flag") `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err"
  Pop-Location

  $up = $false
  for ($i = 0; $i -lt 60 -and -not $up; $i++) {
    Start-Sleep -Seconds 3
    try { $up = ((Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 2).status -eq 'UP') } catch { }
  }
  if (-not $up) { Stop-App; throw "${tag}: app did not start, see $log" }

  # Platform threads show up in a thread dump as http-nio-8080-exec-N; virtual threads do not.
  $pid8080 = (Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -First 1).OwningProcess
  Invoke-K6 "warmup" 100 '20s' $false   # warm the JIT, connections and caches; not recorded
  $dump = & jcmd $pid8080 Thread.print 2>&1
  $platform = @($dump | Select-String 'http-nio-8080-exec').Count
  "   request threads in thread dump: $platform platform (http-nio-8080-exec)"

  Invoke-K6 $tag $Rate $Duration $true

  $logText = (Get-Content $log -Raw) + (Get-Content "$log.err" -Raw)
  $errors = ([regex]::Matches($logText, ' ERROR ')).Count
  $warns = ([regex]::Matches($logText, ' WARN ')).Count
  "   app log: $errors ERROR lines, $warns WARN lines"
  Stop-App
}

docker compose -f "$root\deploy\wiremock\compose.yaml" down | Out-Null
Push-Location $app; docker compose down -v 2>&1 | Out-Null; Pop-Location

"`n== results"
foreach ($f in Get-ChildItem $results -Filter 'vt-*.json' | Sort-Object Name) {
  $m = (Get-Content $f.FullName -Raw | ConvertFrom-Json).metrics
  $created = $m.orders_created.count
  $unavailable = if ($m.orders_rejected_inventory_unavailable) { $m.orders_rejected_inventory_unavailable.count } else { 0 }
  $noStock = if ($m.orders_rejected_insufficient_stock) { $m.orders_rejected_insufficient_stock.count } else { 0 }
  $unexpected = if ($m.orders_unexpected_status) { $m.orders_unexpected_status.count } else { 0 }
  $dropped = if ($m.dropped_iterations) { $m.dropped_iterations.count } else { 0 }
  '{0,-10} req/s={1,6:N1}  p95={2,7:N1}ms  p99={3,8:N1}ms  max={4,8:N1}ms  created={5}  503={6}  409={7}  unexpected={8}  failed={9:P2}  dropped={10}' -f `
    $f.BaseName, $m.http_reqs.rate, $m.http_req_duration.'p(95)', $m.http_req_duration.'p(99)', $m.http_req_duration.max,
    $created, $unavailable, $noStock, $unexpected, $m.http_req_failed.value, $dropped
}
