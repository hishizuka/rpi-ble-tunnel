import Darwin
import Foundation

protocol CommandRunner: AnyObject {
    var result: Int32? { get }
    func start()
    func stop(code: Int32)
}

@main
enum PiLinkCommand {
    static func main() {
        setbuf(stdout, nil)
        let arguments = Array(CommandLine.arguments.dropFirst())
        if arguments.isEmpty || arguments == ["--help"] || arguments == ["-h"] ||
            arguments == ["echo", "--help"] || arguments == ["connect", "--help"] ||
            arguments == ["multiplex", "--help"] || arguments == ["internet", "--help"] ||
            arguments == ["ssh", "--help"] {
            print(Options.help)
            exit(0)
        }
        do {
            let options = try Options.parse(arguments)
            let client: CommandRunner
            switch options.command {
            case "echo": client = try EchoClient(options: options)
            case "connect": client = AutoClient(options: options)
            case "multiplex", "internet": client = MultiplexClient(options: options)
            default: client = SSHClient(options: options)
            }
            let signalSources = [SIGINT, SIGTERM].map { value -> DispatchSourceSignal in
                signal(value, SIG_IGN)
                let source = DispatchSource.makeSignalSource(signal: value, queue: .main)
                source.setEventHandler { client.stop(code: 128 + value) }
                source.resume()
                return source
            }
            client.start()
            withExtendedLifetime(signalSources) {
                while client.result == nil {
                    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.1))
                }
            }
            exit(client.result!)
        } catch {
            FileHandle.standardError.write(Data("PiLink: \(error)\n".utf8))
            exit(2)
        }
    }
}
