import Foundation
import PiLinkCore

struct Options {
    var command = "echo"
    var name: String?
    var identifier: UUID?
    var payload = Array("PiLink BLE echo\n".utf8)
    var timeout: TimeInterval = 30
    var chunkSize = 512
    var port: UInt16 = 2222

    static let help = """
    PiLink — Mac → Raspberry Pi BLE L2CAP

    pilink connect [--name NAME | --id UUID] [--port PORT] [--timeout SECONDS]
    pilink ssh [--name NAME | --id UUID] [--port PORT] [--timeout SECONDS]
    pilink multiplex [--name NAME | --id UUID] [--port PORT] [--timeout SECONDS]
    pilink internet [--name NAME | --id UUID] [--port PORT] [--timeout SECONDS]
    pilink echo [--name NAME | --id UUID] [--message TEXT | --size BYTES]
                [--timeout SECONDS] [--chunk-size BYTES]

      connect         Pi の対応方式を自動選択（Internet を優先）、SSH を公開
      ssh             旧 ssh モードの Pi と単一 SSH ブリッジを公開
      multiplex       mux モードの Pi と SSH ブリッジを公開（最大 8 接続）
      internet        Pi の SOCKS5 接続を Mac のネットワークへ中継、SSH も利用
      echo            echo モードの Pi と全バイトの一致を検証
      --name          Pi のホスト名で接続先を絞り込み（例: raspberrypi）
      --id            CoreBluetooth の peripheral UUID で接続先を指定
      --port          接続コマンドのローカル SSH ポート（既定: 2222）
      --timeout       BLE 接続の制限時間（既定: 30 秒、echo は操作全体）
      --message       echo で UTF-8 文字列を送信
      --size          echo で指定サイズのバイナリを送信（1..16777216）
      --chunk-size    echo の stream write サイズ（既定: 512）

    接続例: ssh -p 2222 -o HostKeyAlias=raspberrypi.local pi@127.0.0.1
    connect は Ctrl-C で終了します。SSH セッションに時間制限はありません。
    """

    static func parse(_ arguments: [String]) throws -> Options {
        guard let command = arguments.first, ["echo", "connect", "ssh", "multiplex", "internet"].contains(command) else {
            throw PiLinkError.invalid("connect / ssh / multiplex / internet / echo を指定してください。--help で使い方を表示します。")
        }
        var options = Options()
        options.command = command
        var index = 1
        var hasPayload = false
        var seen = Set<String>()
        while index < arguments.count {
            let key = arguments[index]
            guard seen.insert(key).inserted else { throw PiLinkError.invalid("引数が重複しています: \(key)") }
            guard index + 1 < arguments.count else { throw PiLinkError.invalid("引数の値がありません: \(key)") }
            if command != "echo", ["--message", "--size", "--chunk-size"].contains(key) {
                throw PiLinkError.invalid("\(key) は echo 専用です")
            }
            if command == "echo", key == "--port" { throw PiLinkError.invalid("--port は connect / multiplex 専用です") }
            let value = arguments[index + 1]
            switch key {
            case "--name":
                guard !value.isEmpty else { throw PiLinkError.invalid("--name が空です") }
                options.name = value
            case "--id":
                guard let id = UUID(uuidString: value) else { throw PiLinkError.invalid("--id が不正です") }
                options.identifier = id
            case "--port":
                guard let port = UInt16(value), port != 0 else { throw PiLinkError.invalid("--port は 1..65535 を指定してください") }
                options.port = port
            case "--message", "--size":
                guard !hasPayload else { throw PiLinkError.invalid("--message と --size は同時に指定できません") }
                hasPayload = true
                if key == "--message" {
                    options.payload = Array(value.utf8)
                    guard !options.payload.isEmpty, options.payload.count <= 16_777_216 else {
                        throw PiLinkError.invalid("メッセージのサイズが不正です")
                    }
                } else {
                    guard let size = Int(value), (1...16_777_216).contains(size) else {
                        throw PiLinkError.invalid("--size は 1..16777216 を指定してください")
                    }
                    options.payload = EchoVerifier.binaryPayload(size: size)
                }
            case "--timeout":
                guard let seconds = Double(value), seconds.isFinite, seconds > 0, seconds <= 3600 else {
                    throw PiLinkError.invalid("--timeout は 0 より大きく 3600 以下を指定してください")
                }
                options.timeout = seconds
            case "--chunk-size":
                guard let size = Int(value), (1...16384).contains(size) else {
                    throw PiLinkError.invalid("--chunk-size は 1..16384 を指定してください")
                }
                options.chunkSize = size
            default: throw PiLinkError.invalid("未対応の引数: \(key)")
            }
            index += 2
        }
        guard options.name == nil || options.identifier == nil else {
            throw PiLinkError.invalid("--name と --id は同時に指定できません")
        }
        return options
    }
}
