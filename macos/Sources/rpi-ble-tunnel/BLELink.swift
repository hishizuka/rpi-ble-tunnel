import CoreBluetooth
import Foundation
import RpiBleTunnelCore

protocol BLELinkDelegate: AnyObject {
    func linkReady(_ link: BLELink)
    func link(_ link: BLELink, opened channel: CBL2CAPChannel)
    func link(_ link: BLELink, failed message: String)
}

func closeL2CAP(_ channel: CBL2CAPChannel) {
    for stream: Stream in [channel.inputStream, channel.outputStream] {
        stream.delegate = nil
        stream.close()
        stream.remove(from: .main, forMode: .default)
    }
}

final class BLELink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let options: Options
    private let capability: UInt32
    private weak var delegate: BLELinkDelegate?
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var values = [String: Data]()
    private var profile: RpiBleTunnelProfile.Discovery?
    private var stopped = false
    private var opening = false
    private var disconnectCompletion: (() -> Void)?
    private(set) var stage = "Bluetooth の初期化"
    var isReady: Bool { profile != nil && !stopped }
    var identifier: UUID? { peripheral?.identifier }
    var selectedCapability: UInt32? {
        guard let profile else { return nil }
        return try? RpiBleTunnelProfile.selectCapability(profile.capabilities)
    }

    func setDelegate(_ delegate: BLELinkDelegate) { self.delegate = delegate }

    init(options: Options, capability: UInt32, delegate: BLELinkDelegate) {
        self.options = options
        self.capability = capability
        self.delegate = delegate
    }

    func start() { central = CBCentralManager(delegate: self, queue: nil) }

    func stop(afterDisconnect completion: (() -> Void)? = nil) {
        guard !stopped else { return }
        stopped = true
        disconnectCompletion = completion
        central?.stopScan()
        if let peripheral, peripheral.state != .disconnected {
            central?.cancelPeripheralConnection(peripheral)
        } else {
            finishDisconnect()
        }
    }

    private func finishDisconnect() {
        let completion = disconnectCompletion
        disconnectCompletion = nil
        completion?()
    }

    func openChannel() {
        guard !stopped, !opening, let profile, let peripheral else {
            fail("L2CAP を開ける状態ではありません"); return
        }
        opening = true
        stage = "L2CAP CoC の接続"
        peripheral.openL2CAPChannel(profile.psm)
    }

    private func fail(_ message: String) {
        guard !stopped else { return }
        delegate?.link(self, failed: message)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard !stopped else { return }
        switch central.state {
        case .poweredOn:
            guard peripheral == nil else { return }
            stage = "rpi-ble-tunnel のスキャン"
            print("rpi-ble-tunnel Service をスキャンしています…")
            if let identifier = options.identifier,
               let known = central.retrievePeripherals(withIdentifiers: [identifier]).first {
                connect(known)
            } else {
                central.scanForPeripherals(withServices: [CBUUID(string: RpiBleTunnelProfile.service)], options: nil)
            }
        case .unauthorized:
            fail("Bluetooth の利用が許可されていません。システム設定 → プライバシーとセキュリティ → Bluetooth を確認してください。")
        case .poweredOff: fail("Mac の Bluetooth が OFF です")
        case .unsupported: fail("この Mac は CoreBluetooth を利用できません")
        case .resetting:
            if peripheral != nil { fail("Bluetooth がリセットされました") }
        case .unknown: break
        @unknown default: fail("不明な Bluetooth 状態です")
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard !stopped, self.peripheral == nil else { return }
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? "(名称なし)"
        if let expected = options.name, expected != name { return }
        if let expected = options.identifier, expected != peripheral.identifier { return }
        print("検出: \(name), id=\(peripheral.identifier), RSSI=\(RSSI)")
        connect(peripheral)
    }

    private func connect(_ peripheral: CBPeripheral) {
        self.peripheral = peripheral
        peripheral.delegate = self
        central.stopScan()
        stage = "GATT 接続"
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard !stopped else { return }
        stage = "GATT Service の取得"
        peripheral.discoverServices([CBUUID(string: RpiBleTunnelProfile.service)])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if stopped { finishDisconnect(); return }
        fail("GATT 接続: \(error?.localizedDescription ?? "失敗")")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if stopped { finishDisconnect(); return }
        fail("BLE が切断されました: \(error?.localizedDescription ?? "接続終了")")
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard !stopped else { return }
        if let error { fail("Service 取得: \(error.localizedDescription)"); return }
        guard let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: RpiBleTunnelProfile.service) }) else {
            fail("rpi-ble-tunnel Service が見つかりません"); return
        }
        stage = "GATT Characteristic の取得"
        peripheral.discoverCharacteristics(
            [RpiBleTunnelProfile.version, RpiBleTunnelProfile.psm, RpiBleTunnelProfile.capabilities].map(CBUUID.init(string:)), for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard !stopped else { return }
        if let error { fail("Characteristic 取得: \(error.localizedDescription)"); return }
        let expected = [RpiBleTunnelProfile.version, RpiBleTunnelProfile.psm, RpiBleTunnelProfile.capabilities]
        guard let characteristics = service.characteristics,
              expected.allSatisfy({ uuid in characteristics.contains { $0.uuid == CBUUID(string: uuid) } }) else {
            fail("必須の GATT Characteristic が不足しています"); return
        }
        stage = "version / PSM / capabilities の読み取り"
        for characteristic in characteristics where expected.contains(characteristic.uuid.uuidString.lowercased()) {
            peripheral.readValue(for: characteristic)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard !stopped, profile == nil else { return }
        if let error { fail("GATT 読み取り: \(error.localizedDescription)"); return }
        guard let value = characteristic.value else { fail("GATT 値が空です"); return }
        values[characteristic.uuid.uuidString.lowercased()] = value
        guard let version = values[RpiBleTunnelProfile.version], let psm = values[RpiBleTunnelProfile.psm],
              let capabilities = values[RpiBleTunnelProfile.capabilities] else { return }
        do {
            let decoded = try RpiBleTunnelProfile.decode(version: version, psm: psm, capabilities: capabilities,
                                                    requiredCapability: capability)
            profile = decoded
            values.removeAll()
            print("GATT: version=\(decoded.version), PSM=\(decoded.psm), capabilities=\(decoded.capabilities)")
            stage = "BLE 接続済み"
            delegate?.linkReady(self)
        } catch { fail(String(describing: error)) }
    }

    func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        opening = false
        guard !stopped else { if let channel { closeL2CAP(channel) }; return }
        if let error { fail("L2CAP 接続: \(error.localizedDescription)"); return }
        guard let channel, channel.inputStream != nil, channel.outputStream != nil else {
            fail("L2CAP stream が取得できません"); return
        }
        stage = "L2CAP 接続済み"
        delegate?.link(self, opened: channel)
    }
}
