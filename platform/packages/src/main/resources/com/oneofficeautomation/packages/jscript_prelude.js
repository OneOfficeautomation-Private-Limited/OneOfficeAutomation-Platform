// Run JavaScript without Node.js: stand-ins for console and process, and a JSON writer, for Windows Script Host (JScript).
function __ooaJson(v) {
  if (v === null || v === undefined) return 'null';
  var t = typeof v;
  if (t == 'number') return isFinite(v) ? String(v) : 'null';
  if (t == 'boolean') return String(v);
  if (t == 'string') return '"' + v.replace(/[\\"\u0000-\u001f\u2028\u2029]/g, function (c) {
    var m = {'"': '\\"', '\\': '\\\\', '\n': '\\n', '\r': '\\r', '\t': '\\t'};
    return m[c] || ('\\u' + ('0000' + c.charCodeAt(0).toString(16)).slice(-4)); }) + '"';
  if (v instanceof Date) return __ooaJson(v.getUTCFullYear() + '-' + ('0' + (v.getUTCMonth() + 1)).slice(-2) + '-' + ('0' + v.getUTCDate()).slice(-2) + 'T'
      + ('0' + v.getUTCHours()).slice(-2) + ':' + ('0' + v.getUTCMinutes()).slice(-2) + ':' + ('0' + v.getUTCSeconds()).slice(-2) + 'Z');
  if (v instanceof Array) { var a = []; for (var i = 0; i < v.length; i++) a.push(__ooaJson(v[i])); return '[' + a.join(',') + ']'; }
  if (t == 'object') { var o = []; for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k)) o.push(__ooaJson(String(k)) + ':' + __ooaJson(v[k])); return '{' + o.join(',') + '}'; }
  return 'null';
}
function __ooaText(a) { var s = []; for (var i = 0; i < a.length; i++) s.push(typeof a[i] == 'object' && a[i] !== null ? __ooaJson(a[i]) : String(a[i])); return s.join(' '); }
var console = { log: function () { WScript.StdOut.WriteLine(__ooaText(arguments)); }, error: function () { WScript.StdErr.WriteLine(__ooaText(arguments)); } };
console.info = console.log; console.warn = console.error;
var JSON = { stringify: function (v) { return __ooaJson(v); } };
var process = { argv: ['cscript', WScript.ScriptFullName], env: {}, exit: function (c) { WScript.Quit(c || 0); },
  stdin: { read: function () { return WScript.StdIn.AtEndOfStream ? '' : WScript.StdIn.ReadAll(); } } };
(function () {
  var e = new Enumerator(new ActiveXObject('WScript.Shell').Environment('PROCESS'));
  for (; !e.atEnd(); e.moveNext()) { var kv = String(e.item()); var i = kv.indexOf('='); if (i > 0) process.env[kv.substring(0, i)] = kv.substring(i + 1); }
})();
function __ooaRead(p) {
  var s = new ActiveXObject('ADODB.Stream'); s.Type = 2; s.Charset = 'utf-8'; s.Open(); s.LoadFromFile(p);
  var t = s.ReadText(); s.Close();
  return t.charCodeAt(0) == 0xFEFF ? t.substring(1) : t;
}
