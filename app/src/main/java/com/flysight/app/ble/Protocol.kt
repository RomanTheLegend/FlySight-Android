package com.flysight.app.ble

import java.util.UUID

object FlySightUuids {
    val FILE_TRANSFER_SERVICE: UUID = UUID.fromString("00000000-cc7a-482a-984a-7f2ed5b3e58f")
    val FT_PACKET_OUT:          UUID = UUID.fromString("00000001-8e22-4541-9d4c-21edae82ed19")
    val FT_PACKET_IN:           UUID = UUID.fromString("00000002-8e22-4541-9d4c-21edae82ed19")
    val CCCD:                   UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE:        UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL:          UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
}

object FtOpcode {
    const val DELETE_FILE: Byte = 0x01
    const val READ_FILE:  Byte = 0x02
    const val WRITE_FILE: Byte = 0x03
    const val LIST_DIR:   Byte = 0x05
    const val FILE_DATA:  Byte = 0x10
    const val FILE_INFO:  Byte = 0x11   // directory entry; no ACK sent by phone
    const val ACK_DATA:   Byte = 0x12
    const val NAK:        Byte = 0xf0.toByte()
    const val ACK:        Byte = 0xf1.toByte()
    const val PING:       Byte = 0xfe.toByte()
    const val CANCEL:     Byte = 0xff.toByte()
}

// Manufacturer ID 0x09DB = Bionic Avionics Inc.
const val MANUF_ID_FLYSIGHT = 0x09DB

const val CONFIG_PATH = "demo_cfg.txt"

// Sentinel RSSI used for bonded devices not currently advertising
const val RSSI_UNKNOWN = Int.MIN_VALUE
