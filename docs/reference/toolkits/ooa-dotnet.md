# .NET, Java and COM

Toolkit `ooa.dotnet`, version 1.0.0. Run inline C# or VB.NET code, and call methods of .NET assemblies, COM objects and Java classes.

[All toolkits](README.md)

## Contents

- **COM**: Call COM method
- **.NET**: Call .NET method
- **Java**: Call Java method
- **.NET sessions**: Call method in session, Close assembly, Create object, Open assembly, Read assembly
- **Code**: Run C# code, Run VB.NET code
- **Conditions**: .NET session is open

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

## .NET sessions

### Call method in session

`ooa.dotnet/call_method` (action)

Call a static method of a type, or a method of an object created in the session, with typed and named arguments (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `dotnet` | Main | Name of the .NET session, from Open assembly. |
| Type | `type` | Text | No |  | Main; choice Call on = A static method of a type | Full type name for a static method. |
| Object name | `object_name` | Text | No |  | Main; choice Call on = A method of a session object | An object made with Create object. |
| Method | `method` | Text | Yes |  | Main | Method name. Example: `Price` |
| Arguments | `arguments` | List | No |  | Main | Arguments in order. |
| Argument types | `argument_types` | List | No |  | More options | The type of each argument, in order: auto, text, int, long, double, decimal, bool, byte, datetime, bytes (base64), text_list, number_list, list or dictionary. |
| Named arguments | `named_arguments` | Dictionary | No |  | More options | Arguments by their parameter names, after the ones in order. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `300` | More options | Maximum run time; the session is closed when it runs out. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | What the method returned. |
| `output` | Text | Anything it wrote to the console. |

*Time limit 3600 s.*

### Close assembly

`ooa.dotnet/close_assembly` (action)

End a .NET session (G4); sessions also end with the run.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `dotnet` | Main | Name of the .NET session, from Open assembly. |

*Time limit 60 s.*

### Create object

`ooa.dotnet/create_object` (action)

Create an object of a type of the open assembly and keep it in the session by a name, for Call method (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `dotnet` | Main | Name of the .NET session, from Open assembly. |
| Type | `type` | Text | Yes |  | Main | Full type name, with its namespace. Example: `Pricing.Calculator` |
| Object name | `object_name` | Text | No | `object` | Main | Name the object is kept by. |
| Arguments | `arguments` | List | No |  | Main | Constructor arguments in order. |
| Argument types | `argument_types` | List | No |  | More options | The type of each argument, in order: auto, text, int, long, double, decimal, bool, byte, datetime, bytes (base64), text_list, number_list, list or dictionary. |
| Named arguments | `named_arguments` | Dictionary | No |  | More options | Arguments by their parameter names, after the ones in order. |

*Time limit 600 s.*

### Open assembly

`ooa.dotnet/open_assembly` (action)

Load a .NET assembly (DLL) into a session kept for the steps after it, so objects it creates keep their state (G4, Windows).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | The .dll file. |
| Session | `session` | Text | No | `dotnet` | Main | Session name the later steps use. |
| Share | `share` | Yes/No | No | No | More options | Agents this one runs use the same session. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `assembly` | Text | The assembly's full name. |

*Time limit 120 s; needs: filesystem.*

### Read assembly

`ooa.dotnet/read_assembly` (action)

List the public types of a .NET assembly with their methods and parameters (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | The .dll file. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `types` | List | Each type: type (full name) and methods (name, static, returns, parameters with name, type, optional). |

*Time limit 120 s; safe to repeat; needs: filesystem.*

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

## Conditions

Used in If, Loop while and Wait until steps.

### .NET session is open

`ooa.dotnet/assembly_session_open` (condition)

True when the .NET session is open (G4).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `dotnet` | Main | Name of the .NET session, from Open assembly. |
