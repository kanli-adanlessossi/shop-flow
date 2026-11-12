<#
.SYNOPSIS
  Plays the payment provider: sends a signed webhook to a locally running order-platform.

.DESCRIPTION
  The webhook endpoint (POST /api/webhooks/payments) only accepts requests signed with the shared secret
  (header X-Payment-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>." + body>), which a plain
  .http file cannot compute. This script builds the body, signs it, sends it and prints the status and
  the answer.

  The secret is read from -Secret, then from $env:PAYMENT_WEBHOOK_SECRET. With the "dev" Spring profile
  the application uses the secret "dev-only-webhook-secret".

.EXAMPLE
  ./http/send-payment-webhook.ps1 -OrderId 5 -Amount 24.25 -Secret dev-only-webhook-secret
  # same event again: the provider retrying (answer: DUPLICATE)
  ./http/send-payment-webhook.ps1 -OrderId 5 -Amount 24.25 -EventId evt_fixed -Secret dev-only-webhook-secret
  # payment that failed (answer: IGNORED)
  ./http/send-payment-webhook.ps1 -OrderId 5 -Amount 24.25 -Type payment.failed -Secret dev-only-webhook-secret
#>
param(
  [Parameter(Mandatory)][long]$OrderId,
  [Parameter(Mandatory)][decimal]$Amount,
  [string]$Type = 'payment.succeeded',
  [string]$EventId = ('evt_' + [guid]::NewGuid().ToString('N').Substring(0, 12)),
  [string]$PaymentId = ('pay_' + [guid]::NewGuid().ToString('N').Substring(0, 8)),
  [string]$Secret = $env:PAYMENT_WEBHOOK_SECRET,
  [string]$BaseUrl = 'http://localhost:8080',
  [switch]$BadSignature
)

if (-not $Secret) { throw 'No secret: pass -Secret or set PAYMENT_WEBHOOK_SECRET.' }

$amountText = $Amount.ToString([System.Globalization.CultureInfo]::InvariantCulture)
$json = '{"eventId":"' + $EventId + '","type":"' + $Type + '","data":{"orderId":' + $OrderId + ',"paymentId":"' + $PaymentId + '","amount":' + $amountText + '}}'
$body = [System.Text.Encoding]::UTF8.GetBytes($json)

$t = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$hmac = New-Object System.Security.Cryptography.HMACSHA256 (, [System.Text.Encoding]::UTF8.GetBytes($(if ($BadSignature) { $Secret + 'x' } else { $Secret })))
$prefix = [System.Text.Encoding]::ASCII.GetBytes("$t.")
$hex = ([System.BitConverter]::ToString($hmac.ComputeHash([byte[]]($prefix + $body))) -replace '-', '').ToLowerInvariant()
$signature = "t=$t,v1=$hex"

Write-Host "POST $BaseUrl/api/webhooks/payments"
Write-Host "  $json"
try {
  $response = Invoke-WebRequest -Uri "$BaseUrl/api/webhooks/payments" -Method Post -Body $body -ContentType 'application/json' `
    -Headers @{ 'X-Payment-Signature' = $signature } -UseBasicParsing
  Write-Host ("-> {0} {1}" -f [int]$response.StatusCode, $response.Content)
}
catch [System.Net.WebException] {
  $reply = $_.Exception.Response
  $text = if ($reply) { (New-Object System.IO.StreamReader $reply.GetResponseStream()).ReadToEnd() } else { $_.Exception.Message }
  Write-Host ("-> {0} {1}" -f [int]$reply.StatusCode, $text)
}
