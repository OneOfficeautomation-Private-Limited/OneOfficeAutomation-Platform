# .NET, Java and COM

Toolkit `ooa.dotnet`, version 1.0.0. Run inline C# or VB.NET code, and call methods of .NET assemblies, COM objects and Java classes.

[All toolkits](README.md)

## Contents

- **COM**: Call COM method
- **.NET**: Call .NET method
- **Java**: Call Java method
- **Code**: Run C# code, Run VB.NET code

## COM

### Call COM method

`ooa.dotnet/call_com_method` (action)

Create a COM object by its ProgID and call a method or read or set a property (Windows), optionally through a path of properties.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Prog ID | `prog_id` | Text | Yes |  | Main | ProgID of the COM class. Example: `Scripting.FileSystemObject` |
| Path | `path` | List | No |  | Main | Properties to go through first, in order, e.g. Workbooks then Item. Example: `["Application"]` |
| Member | `member` | Text | Yes |  | Main | Method or property name. Example: `FolderExists` |
| Kind | `kind` | Text | No | `method` | Main | Call a method, read a property, or set one (its value is the last argument). One of: `method`, `get_property`, `set_property` |
| Arguments | `arguments` | List | No |  | Main | Arguments in order. |
| Quit | `quit` | Yes/No | No | No | More options | Call the object's Quit method at the end (Office applications). |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Stop the call after this many seconds. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the code throws; when off, the error goes to 'error' and the run goes on. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | What the method or property returned (objects as text). |
| `output` | Text | Anything written to the console. |
| `error` | Text | The error, when fail on error is off. |

*Time limit 3600 s.*

## .NET

### Call .NET method

`ooa.dotnet/call_dotnet_method` (action)

Call a static method or property of a .NET type, or create an object and call one of its methods (Windows).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Assembly | `assembly` | Text | No |  | Main | DLL path or assembly name; empty for types of the .NET Framework itself (System.Math). Example: `C:/Tools/Pricing.dll` |
| Type | `type` | Text | Yes |  | Main | Full type name, with its namespace. Example: `System.Math` |
| Member | `member` | Text | Yes |  | Main | Method or property name. Example: `Round` |
| Kind | `kind` | Text | No | `method` | Main | Call a method, or read a property or field. One of: `method`, `get_property` |
| Arguments | `arguments` | List | No |  | Main | Arguments in order; JSON numbers become Int32, Int64, Decimal or Double. |
| Create instance | `create_instance` | Yes/No | No | No | More options; tick Create an object first | Create an object of the type first and call the member on it. |
| Constructor arguments | `constructor_arguments` | List | No |  | More options | Arguments for creating the object. Shown when: `{"create_instance": ["true"]}` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Stop the call after this many seconds. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the code throws; when off, the error goes to 'error' and the run goes on. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | What the method or property returned. |
| `output` | Text | Anything the method wrote to the console. |
| `error` | Text | The exception, when fail on error is off. |

*Time limit 3600 s.*

## Java

### Call Java method

`ooa.dotnet/call_java_method` (action)

Load JAR files and call a static method of a class, or create an object and call one of its methods, in a separate Java process.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Jars | `jars` | List | No |  | Main | JAR files or class folders to load; empty for classes of Java itself. Example: `["lib/pricing.jar"]` |
| Class name | `class_name` | Text | Yes |  | Main | Full class name, with its package. Example: `java.lang.Math` |
| Method | `method` | Text | Yes |  | Main | Method name; the one whose parameters fit the arguments is used. Example: `max` |
| Arguments | `arguments` | List | No |  | Main | Arguments in order; converted to the method's parameter types. |
| Create instance | `create_instance` | Yes/No | No | No | More options; tick Create an object first | Create an object of the class first and call the method on it. |
| Constructor arguments | `constructor_arguments` | List | No |  | More options | Arguments for creating the object. Shown when: `{"create_instance": ["true"]}` |
| Java | `java` | File path | No |  | More options | Java program to use; the runner's own Java, then java on the PATH, when empty. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Stop the call after this many seconds. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the code throws; when off, the error goes to 'error' and the run goes on. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | What the method returned (text, numbers, true/false, lists and maps; other objects as text). |
| `output` | Text | What the method printed. |
| `error` | Text | The exception, when fail on error is off. |

*Time limit 3600 s; needs: filesystem.*

## Code

### Run C# code

`ooa.dotnet/run_csharp` (action)

Run C# statements (the body of a method that gets args and returns a value); compiled by the .NET Framework on Windows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Code | `code` | Text | Yes |  | Main | C# statements; args is an IDictionary<string, object> of the arguments; return a value. Example: `var n = Convert.ToInt32(args["count"]);
return n * 2;` |
| Arguments | `arguments` | Dictionary | No |  | Main | Values the code reads as args["name"]. |
| Usings | `usings` | Text | No |  | More options | Extra namespaces, one per line (System, Collections.Generic, Linq, Text and IO are there). Example: `System.Globalization` |
| Members | `members` | Text | No |  | More options | Other methods or fields of the same static class, for code used more than once. |
| References | `references` | List | No |  | More options | Assemblies the code needs beyond the usual ones: names such as System.Xml, or DLL paths. Example: `["System.Xml"]` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Stop the call after this many seconds. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the code throws; when off, the error goes to 'error' and the run goes on. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | What the code returns (text, numbers, true/false, lists and dictionaries; other objects as text). |
| `output` | Text | What the code wrote with Console.WriteLine. |
| `error` | Text | The exception, when fail on error is off. |

*Time limit 3600 s.*

### Run VB.NET code

`ooa.dotnet/run_vbnet` (action)

Run VB.NET statements (the body of a function that gets args and returns a value); compiled by the .NET Framework on Windows.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Code | `code` | Text | Yes |  | Main | VB.NET statements; args is an IDictionary(Of String, Object) of the arguments; Return a value. Example: `Dim n As Integer = CInt(args("count"))
Return n * 2` |
| Arguments | `arguments` | Dictionary | No |  | Main | Values the code reads as args("name"). |
| Imports | `imports` | Text | No |  | More options | Extra namespaces, one per line. Example: `System.Globalization` |
| Members | `members` | Text | No |  | More options | Other functions or fields of the same module. |
| References | `references` | List | No |  | More options | Assemblies the code needs beyond the usual ones: names or DLL paths. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Stop the call after this many seconds. Range: 1 to  |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Fail the step when the code throws; when off, the error goes to 'error' and the run goes on. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | What the code returns (text, numbers, true/false, lists and dictionaries; other objects as text). |
| `output` | Text | What the code wrote with Console.WriteLine. |
| `error` | Text | The exception, when fail on error is off. |

*Time limit 3600 s.*
