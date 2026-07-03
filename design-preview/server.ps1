$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add('http://localhost:8765/')
$listener.Start()
Write-Host 'Server started on http://localhost:8765/'
while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $path = $ctx.Request.Url.AbsolutePath
    if ($path -eq '/') { $path = '/status-ribbon.html' }
    $file = 'f:\trae-project\design-preview' + $path
    if (Test-Path $file -PathType Leaf) {
        $buffer = [System.IO.File]::ReadAllBytes($file)
        if ($path -match '\.html$') {
            $ctx.Response.ContentType = 'text/html; charset=utf-8'
        } elseif ($path -match '\.css$') {
            $ctx.Response.ContentType = 'text/css'
        } elseif ($path -match '\.js$') {
            $ctx.Response.ContentType = 'application/javascript'
        } else {
            $ctx.Response.ContentType = 'application/octet-stream'
        }
        $ctx.Response.ContentLength64 = $buffer.Length
        $ctx.Response.OutputStream.Write($buffer, 0, $buffer.Length)
    } else {
        $ctx.Response.StatusCode = 404
    }
    $ctx.Response.Close()
}
