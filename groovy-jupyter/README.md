# Groovy Jupyter Kernel

A Jupyter kernel for [Apache Groovy](https://groovy-lang.org/), enabling interactive Groovy programming in Jupyter notebooks.

## Features

- **Full Groovy Support**: Execute Groovy code with complete language features
- **Session State**: Variables persist across cell executions
- **Output Capture**: stdout and stderr are captured and displayed
- **Error Handling**: Structured error reporting with stack traces
- **Jupyter Protocol 5.3**: Full compatibility with Jupyter messaging protocol

## Requirements

- Java 17 or higher
- Jupyter (notebook or lab)
- Gradle (for building from source)

## Quick Start

### Installation

```bash
# Clone the repository
git clone https://github.com/albertocavalcante/groovy-lsp.git
cd groovy-lsp

# Install the kernel
./tools/jupyter/install-kernel.sh
```

For user-only installation (no sudo required):

```bash
./tools/jupyter/install-kernel.sh --user
```

### Usage

**Prerequisite**: Ensure Jupyter is installed. We provide a helper script using [uv](https://github.com/astral-sh/uv):

```bash
./tools/jupyter/install-jupyter.sh
```

Or install manually:

```bash
uv tool install jupyter-core
uv tool install jupyter-client
uv tool install jupyterlab
# or
pip install jupyter
```

Start Jupyter and select "Groovy" from the kernel list:

```bash
jupyter notebook
# or
jupyter lab
```

### Example

```groovy
// Define a variable
def greeting = "Hello from Groovy!"
println greeting

// Variables persist across cells
greeting.toUpperCase()
```

## Development

### Building

```bash
# Build the fat JAR
./gradlew :groovy-jupyter:shadowJar

# Run tests
./gradlew :groovy-jupyter:test
```

### Project Structure

```
groovy-jupyter/
├── src/main/kotlin/
│   └── com/github/albertocavalcante/groovyjupyter/
│       ├── Main.kt                    # Entry point
│       ├── handlers/                  # Message handlers
│       │   ├── ExecuteHandler.kt      # Code execution
│       │   ├── KernelInfoHandler.kt   # Kernel metadata
│       │   └── ShutdownHandler.kt     # Graceful shutdown
│       ├── kernel/
│       │   └── KernelServer.kt        # Main polling loop
│       ├── protocol/                  # Jupyter protocol types
│       ├── security/                  # HMAC signing
│       └── zmq/                       # ZMQ messaging
├── src/main/resources/
│   └── kernel/
│       └── kernel.json                # Kernel specification
└── scripts/
    └── install-kernel.sh              # Installation script
```

### Dependencies

| Dependency | Purpose |
|------------|---------|
| [JeroMQ](https://github.com/zeromq/jeromq) | ZeroMQ messaging |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | JSON handling |
| [groovy-repl](../groovy-repl) | Groovy execution engine |

## Acknowledgments

Implementation inspired by:
- [lappsgrid-incubator/jupyter-groovy-kernel](https://github.com/lappsgrid-incubator/jupyter-groovy-kernel)
- [Jupyter Messaging Protocol](https://jupyter-client.readthedocs.io/en/stable/messaging.html)

## License

Apache 2.0 - See [LICENSE](../LICENSE) for details.
