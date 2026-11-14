# End-to-end check of order-platform (8080) with inventory-service (8081). Dev credentials only (see compose.yaml).
$ErrorActionPreference = 'Stop'
$wh = 'c:/PUBLIC/PROJECTS/JOB PREP/dev/shop-flow/.worktrees/inventory-service-prod/order-platform/http/send-payment-webhook.ps1'
$kc = 'http://localhost:8180/realms/shopflow/protocol/openid-connect/token'
function Tok($body) { (Invoke-RestMethod -Method Post $kc -Body $body).access_token }
$alice = Tok @{grant_type='password';client_id='shopflow-web';username='alice';password='alice-pass'}
$bob   = Tok @{grant_type='password';client_id='shopflow-web';username='bob';password='bob-pass'}
$svc   = Tok @{grant_type='client_credentials';client_id='shopflow-service';client_secret='shopflow-service-secret'}
function Call($method, $url, $token, $body) {
  $h = @{}; if ($token) { $h.Authorization = "Bearer $token" }
  $p = @{Uri=$url; Method=$method; Headers=$h; UseBasicParsing=$true}
  if ($body) { $p.Body = ($body | ConvertTo-Json -Depth 5); $p.ContentType = 'application/json' }
  try {
    $r = Invoke-WebRequest @p
    [pscustomobject]@{Status=[int]$r.StatusCode; Body=$r.Content}
  } catch [System.Net.WebException] {
    $reply = $_.Exception.Response
    $text = if ($reply) { (New-Object System.IO.StreamReader $reply.GetResponseStream()).ReadToEnd() } else { $_.Exception.Message }
    [pscustomobject]@{Status=[int]$reply.StatusCode; Body=$text}
  }
}
function Stock($sku) { (Call GET "http://localhost:8081/api/stock/$sku" $svc).Body | ConvertFrom-Json | % available }
function Sql($q) { (docker exec inventory-service-prod-postgres-inventory-1 psql -U inventory -d inventory -tA -c $q) -join ' ' }
function Wait-Until($sb) { for ($i = 0; $i -lt 30; $i++) { $v = & $sb; if ($v) { return $v }; Start-Sleep 1 }; $null }
function Order($sku, $qty) { Call POST 'http://localhost:8080/api/orders' $alice @{customerId='alice'; lines=@(@{sku=$sku; quantity=$qty})} }

Write-Host '== 1. place order (alice, 3 x SKU-001): stock drops'
$s0 = Stock 'SKU-001'
$o1 = Order 'SKU-001' 3
Write-Host "stock before=$s0, POST /api/orders -> $($o1.Status)"
$id1 = ($o1.Body | ConvertFrom-Json).id
$s1 = Stock 'SKU-001'
Write-Host "order $id1, stock after=$s1 (expected $($s0 - 3))"

Write-Host '== 2. cancel the order: stock returns'
$c = Call PATCH "http://localhost:8080/api/orders/$id1/status" $bob @{status='CANCELLED'}
Write-Host "PATCH CANCELLED (bob, admin; a customer gets 403) -> $($c.Status) $($c.Body)"
$s2 = Wait-Until { $v = Stock 'SKU-001'; if ($v -eq $s0) { $v } }
Write-Host "stock after cancel=$s2 (expected $s0)"

Write-Host '== 3. pay via webhook, then SHIPPED'
$t0 = Stock 'SKU-002'
$o2 = Order 'SKU-002' 2
$od = $o2.Body | ConvertFrom-Json
$id2 = $od.id
Write-Host "order $id2 -> $($o2.Status), amount=$($od.totals.amount), stock SKU-002 $t0 -> $(Stock 'SKU-002')"
$resId = Sql "select reservation_id from reservation order by created_at desc limit 1"
Write-Host "reservation $resId status=$(Sql "select status from reservation where reservation_id='$resId'")"
& $wh -OrderId $id2 -Amount $od.totals.amount -Secret dev-only-webhook-secret
$st = Wait-Until { $v = (Call GET "http://localhost:8080/api/orders/$id2" $alice).Body | ConvertFrom-Json; if ($v.status -eq 'CONFIRMED') { $v.status } }
Write-Host "order status after webhook=$st"
$rs = Wait-Until { $v = Sql "select status from reservation where reservation_id='$resId'"; if ($v -eq 'CONFIRMED') { $v } }
Write-Host "reservation status=$rs"
$p = Call PATCH "http://localhost:8080/api/orders/$id2/status" $bob @{status='SHIPPED'}
Write-Host "PATCH SHIPPED (bob) -> $($p.Status)"
$rs = Wait-Until { $v = Sql "select status from reservation where reservation_id='$resId'"; if ($v -eq 'SHIPPED') { $v } }
Write-Host "reservation status=$rs; stock SKU-002 now $(Stock 'SKU-002') (expected $($t0 - 2))"
$d = Call DELETE "http://localhost:8081/api/stock/reservations/$resId" $svc
Write-Host "DELETE reservation (SHIPPED) -> $($d.Status) $($d.Body)"
Write-Host "stock SKU-002 after release attempt: $(Stock 'SKU-002')"

Write-Host '== 4. insufficient stock'
$big = Order 'SKU-003' 100000
Write-Host "POST /api/orders 100000 x SKU-003 -> $($big.Status) $($big.Body)"
Write-Host "stock SKU-003 unchanged: $(Stock 'SKU-003')"

Write-Host '== 5. inventory without token'
$n = Call GET 'http://localhost:8081/api/stock/SKU-001' $null
Write-Host "GET /api/stock/SKU-001 no token -> $($n.Status)"
$n2 = Call POST 'http://localhost:8081/api/stock/reservations' $null @{reservationId='x'; lines=@(@{sku='SKU-001'; quantity=1})}
Write-Host "POST reservations no token -> $($n2.Status)"

