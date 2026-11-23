package dev.balaji.rem.data.contract

import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository

/** An alarm repository and the barcode repository that holds the barcodes of its alarms, on one store. */
class AlarmStorage(val alarms: AlarmRepository, val barcodes: BarcodeRepository)
