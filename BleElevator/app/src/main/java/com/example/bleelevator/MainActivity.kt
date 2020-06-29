package com.example.bleelevator

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT32
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.call_dialog.view.*
import org.jetbrains.anko.alert
import timber.log.Timber
import java.io.IOException
import java.util.*
import kotlin.collections.ArrayList

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class MainActivity : AppCompatActivity() {
    lateinit var context: Context

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val bleScanner by lazy {

        bluetoothAdapter.bluetoothLeScanner
    }
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        Log.i(
                            "BluetoothGattCallback",
                            "Wrote to characteristic $uuid | value: ${value}"
                        )
                        sentCommand = true
                        runOnUiThread {  calledBle(this@MainActivity) }
                        bluetoothGatt = gatt
                    }
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                        Log.e("BluetoothGattCallback", "Write exceeded connection ATT MTU!")
                    }
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                        Log.e("BluetoothGattCallback", "Write not permitted for $uuid!")
                    }
                    else -> {
                        Log.e(
                            "BluetoothGattCallback",
                            "Characteristic write failed for $uuid, error: $status"
                        )
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            with(gatt) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Timber.w("Discovered ${this?.services?.size} services for ${this!!.device.address}.")
                    // See implementation just above this section
                    printGattTable()
                    // Consider connection setup as complete here
                    isConnected = true
                    isDisconnected = false
                    bluetoothGatt = gatt
                    runOnUiThread {
                        if (isConnected == true && isDisconnected == false && sentCommand == false) {
                            dismissProgressBar()
                            connectedToast(this@MainActivity)
                            callElevatorDialog(
                                bluetoothGatt!!.getService(elevatorServiceUUID).getCharacteristic(
                                    elevatorCharacteristicUUID
                                )
                            )
                            countToDisconnect(bluetoothGatt)
                        }
                    }



                } else {
                    Timber.e("Service discovery failed due to status $status")
                }
            }
        }


        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Timber.w("onConnectionStateChange: connected to $deviceAddress")
                    bluetoothGatt = gatt
                   runOnUiThread {  Handler(Looper.getMainLooper()).post {
                       gatt?.discoverServices()
                   }}

                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) { /* Omitted for brevity */
                    Timber.e("onConnectionStateChange: disconnected from $deviceAddress")
                    isDisconnected = true
                    gatt.close()

                }
            } else { /* Omitted for brevity */
                Timber.e("onConnectionStateChange: status $status encountered for $deviceAddress!")
                isDisconnected = true
                gatt.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (indexQuery != -1) { // A scan result already exists with the same address
                scanResults[indexQuery] = result
                scanResultAdapter.notifyItemChanged(indexQuery)
            } else {
                with(result.device) {
                    Log.i(
                        "ScanCallback",
                        "Found BLE device! Name: ${name ?: "Unnamed"}, address: $address"
                    )
                        scanResults.clear()
                        stopBleScan()
                        result.device.connectGatt(applicationContext, true, gattCallback)
                        showProgressBar()

                }
                scanResults.add(result)
                scanResultAdapter.notifyItemInserted(scanResults.size - 1)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("ScanCallback", "onScanFailed: code $errorCode")
        }
    }
    private var isScanning = false
        set(value) {
            field = value
            runOnUiThread { scan_button.text = if (value) "Stop Scan" else "Scan" }
        }

    val isLocationPermissionGranted
        get() = hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) ==
                PackageManager.PERMISSION_GRANTED
    }

    private val scanResults = mutableListOf<ScanResult>()
    private val scanResultAdapter: ScanResultAdapter by lazy {
        ScanResultAdapter(scanResults) { result ->
            // User tapped on a scan result
            if (isScanning) {
                stopBleScan()
            }
            with(result.device) {
                Log.w("ScanResultAdapter", "Connecting to $address")
                //connectGatt(applicationContext,false,gattCallback)
            }
        }
    }

    private fun disconnectGatt() {
        isConnected = false

        if (isConnected == false) {
            isDisconnected = true
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            Timber.w("disconnected")
        }
    }

    private fun countToDisconnect(device: BluetoothGatt?) {
        val timer = object : CountDownTimer(8000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                Timber.w("countdown : " + millisUntilFinished / 1000)
            }

            override fun onFinish() {
                try {

                    runOnUiThread {
                        alert {
                            title = "disconnected"
                            message = "please resart app and try again"
                            positiveButton("OK") {
                                if (isConnected == true && sentCommand == false && isDisconnected == false) {
                                    isDisconnected = true
                                    bluetoothGatt?.disconnect()
                                    disconnectGatt()
                                    disconnectedToast(this@MainActivity)
                                }
                                if (isConnected == true && sentCommand == true && isDisconnected == false) {
                                    isDisconnected = true
                                    sentCommand = false
                                    disconnectGatt()
                                    bluetoothGatt?.disconnect()
                                    disconnectedToast(this@MainActivity)

                                }
                            }
                        }.show()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        timer.start()

    }

    companion object {
        private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
        private const val LOCATION_PERMISSION_REQUEST_CODE = 2
        var elevatorServiceUUID: UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")
        var elevatorCharacteristicUUID: UUID =
            UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        var someServiceUUID: UUID = UUID.fromString("55107dc5-8453-52fe-2c44-f28add34040b")
        var someCharacteristicUUID: UUID = UUID.fromString("3e605366-1b06-3b24-689d-fccd0a0dfaf0")
        var bluetoothGatt: BluetoothGatt? = null
        var isConnected = false
        var sentCommand = false
        var isDisconnected = true
        //  private lateinit var device: BluetoothDevice
        // val elevatorService = bluetoothGatt?.getService(elevatorServiceUUID)?.getCharacteristic(elevatorCharacteristicUUID)
        val filters  = ArrayList<ScanFilter>()

    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    val filter = ScanFilter.Builder().setServiceUuid(
        ParcelUuid.fromString(elevatorServiceUUID.toString())
    ).build()

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Timber.plant(Timber.DebugTree())
        context = applicationContext
        scan_button.setOnClickListener {
            if (isScanning) {
                stopBleScan()
            } else {
                startBleScan()

            }

        }
        setupRecyclerView()

    }

    private fun setupRecyclerView() {
        scan_results_recycler_view.apply {
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(
                this@MainActivity,
                RecyclerView.VERTICAL,
                false
            )
            isNestedScrollingEnabled = false
        }

        val animator = scan_results_recycler_view.itemAnimator
        if (animator is SimpleItemAnimator) {
            animator.supportsChangeAnimations = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (!bluetoothAdapter.isEnabled) {
            promptEnableBluetooth()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            ENABLE_BLUETOOTH_REQUEST_CODE -> {
                if (resultCode != Activity.RESULT_OK) {
                    promptEnableBluetooth()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) {
                    requestLocationPermission()
                } else {
                    startBleScan()
                }
            }
        }
    }

    private fun promptEnableBluetooth() {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, Companion.ENABLE_BLUETOOTH_REQUEST_CODE)
        }
    }


    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startBleScan() {
        filters.add(filter)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isLocationPermissionGranted) {
            requestLocationPermission()
        } else {
            scanResults.clear()
            scanResultAdapter.notifyDataSetChanged()
            bleScanner.startScan(filters, scanSettings, scanCallback)
            isScanning = true
        }
    }

    private fun stopBleScan() {
        bleScanner.stopScan(scanCallback)
        isScanning = false
    }

    private fun requestLocationPermission() {
        if (isLocationPermissionGranted) {
            return
        }
        runOnUiThread {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Location permission required")
            builder.setMessage(
                "Starting from Android M (6.0), the system requires apps to be granted " +
                        "location access in order to scan for BLE devices."
            )
            builder.setCancelable(false)
            builder.setPositiveButton(android.R.string.yes) { dialog, which ->
                requestPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    LOCATION_PERMISSION_REQUEST_CODE
                )

            }
            builder.show()
        }
    }

    private fun Activity.requestPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    fun BluetoothGattCharacteristic.isReadable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    fun BluetoothGattCharacteristic.isWritable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return properties and property != 0
    }

    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, payload: Int) {
        try {
            val writeType = when {
                characteristic.isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.isWritableWithoutResponse() -> {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                else -> error("Characteristic ${characteristic.uuid} cannot be written to")
            }




            bluetoothGatt?.let { gatt ->
                characteristic.writeType = writeType
                characteristic.setValue(payload, FORMAT_UINT32, 0)
                if (isConnected == true && isDisconnected == false && sentCommand == false) {
                    gatt.writeCharacteristic(characteristic)

                }

            } ?: error("Not connected to a BLE device!")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun callElevatorDialog(
        characteristic: BluetoothGattCharacteristic

    ) {
        val mDialogView = LayoutInflater.from(this).inflate(R.layout.call_dialog, null)
        val builder =
            AlertDialog.Builder(this).setView(mDialogView).setTitle("Please enter floor number")
        val mAlertDialog = builder.show()
        mDialogView.dialogCallBtn.setOnClickListener {
            val floorNumberByte = mDialogView.dialogFloor.text
            try {
                writeCharacteristic(characteristic, floorNumberByte.toString().toInt())
            }catch (e:IOException){
                Toast.makeText(this,"cannot call",Toast.LENGTH_LONG)
            }

            mAlertDialog.dismiss()


        }
    }

    private fun BluetoothGatt.printGattTable() {
        if (services.isEmpty()) {
            Log.i(
                "printGattTable",
                "No service and characteristic available, call discoverServices() first?"
            )
            return
        }
        services.forEach { service ->
            val characteristicsTable = service.characteristics.joinToString(
                separator = "\n|--",
                prefix = "|--"
            ) { it.uuid.toString() }
            Log.i(
                "printGattTable",
                "\nService ${service.uuid}\nCharacteristics:\n$characteristicsTable"
            )
        }
    }

    private fun disconnectAfterWrite(gatt: BluetoothGatt) {
        try {
            gatt.disconnect()
            gatt.close()
            isConnected = false
            sentCommand = false
            Timber.w("disconnected after write")


        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    private fun restart() {
        val intent = getIntent()
        finish()
        startActivity(intent)
    }

    private fun String.hexToBytes() =
        this.chunked(2).map { it.toUpperCase(Locale.US).toInt(16).toByte() }.toByteArray()

    fun String.hexStringToByteArray() =
        ByteArray(this.length / 2) { this.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun ByteArray.toHexString(): String =
        joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }
//    fun dectoHex(a: Editable): String? {
//        return Integer.toHexString(a)
//    }
    private fun showProgressBar(): Unit {
    progressBar.visibility = View.VISIBLE
}

    private fun dismissProgressBar() {
        progressBar.visibility = View.INVISIBLE
    }
    fun connectedToast(view: MainActivity) {
        KCustomToast.infoToast(this,"You are now connected",
            KCustomToast.GRAVITY_BOTTOM, ResourcesCompat.getFont(applicationContext,R.font.bad_script))
    }
    fun disconnectedToast(view: MainActivity) {
        KCustomToast.infoToast(this,"You are now disconnected",
            KCustomToast.GRAVITY_BOTTOM, ResourcesCompat.getFont(applicationContext,R.font.bad_script))
    }
    fun calledBle(view: MainActivity) {
        KCustomToast.infoToast(this,"You have called the elevator",
            KCustomToast.GRAVITY_BOTTOM, ResourcesCompat.getFont(applicationContext,R.font.bad_script))
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main,menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId){
            R.id.action_help -> {
                val intent = Intent(this,HelpActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_about ->{
                val intent = Intent(this,AboutActivity::class.java)
                startActivity(intent)
                true
            } else -> {
                super.onOptionsItemSelected(item)
            }
        }

    }
}

