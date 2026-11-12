<#
.SYNOPSIS
  Changes how the WireMock stand-ins behave at runtime: delays and faults, no restart needed.

.DESCRIPTION
  Start them first:  docker compose -f deploy/wiremock/compose.yaml up -d
  inventory -> http://localhost:8081, payments -> http://localhost:8082.

  Runtime changes live in memory only. -Mode ok puts the service back to its file stubs.

.PARAMETER Service   inventory | payments
.PARAMETER Mode
  ok                restore the file stubs and remove all delays
  slow              keep normal answers, add the delay given by -DelayMs / -MaxDelayMs
  conflict          409 problem body            not-found      404
  bad-request       400                         error          500
  unavailable       503                         gateway-timeout 504
  reset-connection  TCP reset, no response      empty          connection closed, empty response
  malformed         broken chunked response     garbage        random bytes, then close
  flaky             answer 500 -Failures times, then fall back to the normal stub
.PARAMETER DelayMs     Fixed delay added to every response (also combines with the modes above).
.PARAMETER MaxDelayMs  With -DelayMs: uniform random delay between the two values.
.PARAMETER Failures    For -Mode flaky: how many calls fail before one succeeds (default 2).
.PARAMETER UrlPattern  Regex for the paths a fault applies to (default: all). Example: '/api/stock/reservations'.
.PARAMETER Show        List the active stubs and the global delay.

.EXAMPLE
  ./deploy/wiremock/wiremock.ps1 inventory slow -DelayMs 3000          # beyond the 2s read timeout
  ./deploy/wiremock/wiremock.ps1 inventory flaky -Failures 2           # exercises @Retryable
  ./deploy/wiremock/wiremock.ps1 inventory conflict                    # insufficient stock
  ./deploy/wiremock/wiremock.ps1 payments reset-connection
  ./deploy/wiremock/wiremock.ps1 inventory ok
#>
param(
  [Parameter(Mandatory, Position = 0)][ValidateSet('inventory', 'payments')][string]$Service,
  [Parameter(Position = 1)][ValidateSet('ok', 'slow', 'conflict', 'not-found', 'bad-request', 'error', 'unavailable',
    'gateway-timeout', 'reset-connection', 'empty', 'malformed', 'garbage', 'flaky')][string]$Mode = 'ok',
  [int]$DelayMs = 0,
  [int]$MaxDelayMs = 0,
  [int]$Failures = 2,
  [string]$UrlPattern = '.*',
  [switch]$Show
)

$ErrorActionPreference = 'Stop'
$port = @{ inventory = 8081; payments = 8082 }[$Service]
$admin = "http://localhost:$port/__admin"

function Invoke-Admin($method, $path, $body) {
  $request = @{ Method = $method; Uri = "$admin$path"; ContentType = 'application/json' }
  if ($null -ne $body) { $request.Body = ($body | ConvertTo-Json -Depth 10) }
  Invoke-RestMethod @request
}

try { $null = Invoke-RestMethod "$admin/health" } catch {
  throw "WireMock '$Service' is not reachable on port $port. Run: docker compose -f deploy/wiremock/compose.yaml up -d"
}

if ($Show) {
  (Invoke-Admin GET '/mappings' $null).mappings |
    ForEach-Object { '{0,-3} {1,-8} {2,-40} {3}' -f $_.priority, $_.request.method, ($_.request.urlPath + $_.request.urlPathPattern + $_.request.urlPattern), $_.name } 
  'global settings: ' + ((Invoke-Admin GET '/settings' $null) | ConvertTo-Json -Compress)
  return
}

# Back to the file stubs, then apply what was asked for.
$null = Invoke-Admin POST '/mappings/reset' $null
$null = Invoke-Admin POST '/scenarios/reset' $null

$settings = @{ fixedDelay = $null; delayDistribution = $null }
if ($DelayMs -gt 0 -and $MaxDelayMs -gt $DelayMs) {
  $settings.delayDistribution = @{ type = 'uniform'; lower = $DelayMs; upper = $MaxDelayMs }
} elseif ($DelayMs -gt 0) {
  $settings.fixedDelay = $DelayMs
}
$null = Invoke-Admin PUT '/settings' @{ extended = @{}; fixedDelay = $settings.fixedDelay; delayDistribution = $settings.delayDistribution }

function New-Override($response, $extra = @{}) {
  # Priority 1 beats the file stubs (priority 5).
  $stub = @{ priority = 1; request = @{ urlPathPattern = $UrlPattern }; response = $response; name = "override: $Mode" }
  foreach ($k in $extra.Keys) { $stub[$k] = $extra[$k] }
  $null = Invoke-Admin POST '/mappings' $stub
}

function Problem($status, $title) {
  @{ status = $status; headers = @{ 'Content-Type' = 'application/problem+json' }
     jsonBody = @{ status = $status; title = $title; detail = "WireMock $Mode" } }
}

switch ($Mode) {
  'ok'   { }
  'slow' { if ($DelayMs -le 0) { throw '-Mode slow needs -DelayMs.' } }
  'conflict'        { New-Override (Problem 409 'Conflict') }
  'not-found'       { New-Override (Problem 404 'Not Found') }
  'bad-request'     { New-Override (Problem 400 'Bad Request') }
  'error'           { New-Override (Problem 500 'Internal Server Error') }
  'unavailable'     { New-Override (Problem 503 'Service Unavailable') }
  'gateway-timeout' { New-Override (Problem 504 'Gateway Timeout') }
  'reset-connection' { New-Override @{ fault = 'CONNECTION_RESET_BY_PEER' } }
  'empty'            { New-Override @{ fault = 'EMPTY_RESPONSE' } }
  'malformed'        { New-Override @{ fault = 'MALFORMED_RESPONSE_CHUNK' } }
  'garbage'          { New-Override @{ fault = 'RANDOM_DATA_THEN_CLOSE' } }
  'flaky' {
    # One stub per failure, chained with a scenario. When the chain is used up no override matches
    # and the normal file stub answers.
    for ($i = 0; $i -lt $Failures; $i++) {
      $state = if ($i -eq 0) { 'Started' } else { "fail-$i" }
      New-Override (Problem 500 'Internal Server Error') @{
        scenarioName = 'flaky'; requiredScenarioState = $state; newScenarioState = "fail-$($i + 1)" }
    }
  }
}

$delayText = if ($settings.delayDistribution) { "$DelayMs-$MaxDelayMs ms (uniform)" } elseif ($DelayMs -gt 0) { "$DelayMs ms" } else { 'none' }
"$Service on :$port -> mode '$Mode', delay $delayText" + $(if ($Mode -notin 'ok', 'slow') { ", paths $UrlPattern" } else { '' })
