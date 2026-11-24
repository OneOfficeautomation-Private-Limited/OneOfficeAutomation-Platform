<#
UT invoice tools: sample PowerShell script for agents (Run PowerShell script).
Parameters by position: Currency, then VatRate. Standard input: a JSON object with "lines" (qty, price).
Prints one JSON object: lines, net, vat, gross, currency, computer.
#>
param([string]$Currency = "EUR", [double]$VatRate = 0.2)
$ErrorActionPreference = "Stop"
$data = [Console]::In.ReadToEnd() | ConvertFrom-Json
$net = 0.0
foreach ($line in $data.lines) { $net += [double]$line.qty * [double]$line.price }
$net = [math]::Round($net, 2)
$vat = [math]::Round($net * $VatRate, 2)
[pscustomobject]@{
    lines    = @($data.lines).Count
    net      = $net
    vat      = $vat
    gross    = [math]::Round($net + $vat, 2)
    currency = $Currency.ToUpper()
    computer = $env:COMPUTERNAME -ne $null
} | ConvertTo-Json -Compress
exit 0
