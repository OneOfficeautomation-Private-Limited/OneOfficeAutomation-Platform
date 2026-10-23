# Demo order-entry application for the desktop use cases (Windows Forms; no installation needed).
#   powershell -ExecutionPolicy Bypass -File order-entry.ps1 [-Out orders.csv] [-Demo]
# Controls have stable names (AutomationId): txtCustomer, txtAmount, cboKind, chkUrgent, txtNotes, btnSave, lblStatus, gridOrders.
# A wrong entry opens a "Validation" window (lblMessage, btnOk) without blocking the main window.
param([string]$Out = "", [switch]$Demo)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
$inv = [Globalization.CultureInfo]::InvariantCulture

$f = New-Object Windows.Forms.Form
$f.Text = 'OOA Order Entry'; $f.Width = 660; $f.Height = 560; $f.StartPosition = 'CenterScreen'

function New-Label([string]$text, [int]$y) {
  $l = New-Object Windows.Forms.Label; $l.Text = $text; $l.Left = 16; $l.Top = $y + 3; $l.Width = 100; $f.Controls.Add($l)
}
function New-Box([string]$name, [string]$label, [int]$y) {
  New-Label $label $y
  $b = New-Object Windows.Forms.TextBox; $b.Name = $name; $b.AccessibleName = $label; $b.Left = 120; $b.Top = $y; $b.Width = 300
  $f.Controls.Add($b); return $b
}
$customer = New-Box 'txtCustomer' 'Customer' 16
$amount = New-Box 'txtAmount' 'Amount' 48
New-Label 'Kind' 80
$kind = New-Object Windows.Forms.ComboBox; $kind.Name = 'cboKind'; $kind.AccessibleName = 'Kind'; $kind.DropDownStyle = 'DropDownList'
$kind.Left = 120; $kind.Top = 80; $kind.Width = 160; [void]$kind.Items.AddRange(@('Standard', 'Express', 'Return')); $kind.SelectedIndex = 0; $f.Controls.Add($kind)
$urgent = New-Object Windows.Forms.CheckBox; $urgent.Name = 'chkUrgent'; $urgent.Text = 'Urgent'; $urgent.Left = 300; $urgent.Top = 80; $f.Controls.Add($urgent)
New-Label 'Notes' 112
$notes = New-Object Windows.Forms.TextBox; $notes.Name = 'txtNotes'; $notes.AccessibleName = 'Notes'; $notes.Multiline = $true
$notes.Left = 120; $notes.Top = 112; $notes.Width = 480; $notes.Height = 56; $f.Controls.Add($notes)
$save = New-Object Windows.Forms.Button; $save.Name = 'btnSave'; $save.Text = 'Save'; $save.Left = 120; $save.Top = 180; $save.Width = 90; $f.Controls.Add($save)
$status = New-Object Windows.Forms.Label; $status.Name = 'lblStatus'; $status.Text = 'Ready'   # no AccessibleName: UI Automation then reads its text
$status.Left = 230; $status.Top = 185; $status.Width = 360; $f.Controls.Add($status)

$grid = New-Object Windows.Forms.DataGridView; $grid.Name = 'gridOrders'; $grid.AccessibleName = 'Orders'
$grid.Left = 16; $grid.Top = 220; $grid.Width = 610; $grid.Height = 280; $grid.ReadOnly = $true; $grid.AllowUserToAddRows = $false
foreach ($c in 'Id', 'Customer', 'Amount', 'Kind', 'Status') { [void]$grid.Columns.Add($c, $c) }
$f.Controls.Add($grid)
if ($Demo) {
  [void]$grid.Rows.Add('ORD-0901', 'Acme', '120.00', 'Standard', 'Open')
  [void]$grid.Rows.Add('ORD-0902', 'Globex', '75.50', 'Express', 'Closed')
  [void]$grid.Rows.Add('ORD-0903', 'Initech', '300.00', 'Standard', 'Open')
  [void]$grid.Rows.Add('ORD-0904', 'Umbrella', '42.00', 'Return', 'Open')
}

function Show-Problem([string]$message) {
  $v = New-Object Windows.Forms.Form; $v.Text = 'Validation'; $v.Width = 340; $v.Height = 150; $v.StartPosition = 'CenterParent'; $v.TopMost = $true
  $m = New-Object Windows.Forms.Label; $m.Name = 'lblMessage'; $m.Text = $message; $m.Left = 16; $m.Top = 16; $m.Width = 290; $v.Controls.Add($m)
  $ok = New-Object Windows.Forms.Button; $ok.Name = 'btnOk'; $ok.Text = 'OK'; $ok.Left = 220; $ok.Top = 60; $ok.Add_Click({ $v.Close() }.GetNewClosure()); $v.Controls.Add($ok)
  $v.Show($f)
}

$script:n = 0
$save.Add_Click({
  $a = 0.0
  if ([string]::IsNullOrWhiteSpace($customer.Text)) { Show-Problem 'Customer is required'; return }
  if (-not [double]::TryParse($amount.Text, [Globalization.NumberStyles]::Float, $inv, [ref]$a)) { Show-Problem 'Amount must be a number'; return }
  $script:n++
  $id = 'ORD-{0:D4}' -f (1000 + $script:n)
  [void]$grid.Rows.Add($id, $customer.Text, $a.ToString('0.00', $inv), $kind.Text, 'Open')
  if ($Out) {
    $dir = Split-Path -Parent $Out
    if ($dir -and -not (Test-Path $dir)) { [void](New-Item -ItemType Directory -Path $dir) }
    if (-not (Test-Path $Out)) { Set-Content -Path $Out -Value 'Id,Customer,Amount,Kind,Urgent' -Encoding UTF8 }
    Add-Content -Path $Out -Value ('{0},{1},{2},{3},{4}' -f $id, $customer.Text, $a.ToString('0.00', $inv), $kind.Text, $(if ($urgent.Checked) { 'yes' } else { 'no' })) -Encoding UTF8
  }
  $status.Text = "Saved $id"
  $customer.Text = ''; $amount.Text = ''; $urgent.Checked = $false
})
[void]$f.ShowDialog()
