package com.example.smd

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.example.smd.databinding.ActivityMainBinding
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {

    lateinit var bindingClass: ActivityMainBinding

    val server: String = "SERVER"
    val username: String = "USERNAME"
    val password: String = "PASSWORD"
    var nameServ: String = "im.jpg"
    var nameRes: String = "im_res.png"
    val nameGet: String = "/storage/emulated/0/Pictures/new.png"
    var dstUri: Uri? = null
    var srcUri: Uri? = null

    var photoOrient: Int = 0

    private val IMAGE_CAPTURE_CODE: Int = 1001
    private val IMAGE_GALLERY_CODE: Int = 1002

    private var PROCESS_MODE = 0
    private var PROCESS_MODE_SERVER = 1
    private var PROCESS_MODE_LOCAL = 2

    private var SC = 101
    private var SC_START = 100
    private val SC_UPLOAD_ON_SERVER = 201
    private val SC_PROCESSED_ON_SERVER = 202
    private val SC_UPLOAD_FROM_SERVER = 203
    private val SC_READY = 102
    private val SC_ERROR = 900

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindingClass = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bindingClass.root)
        SC = SC_START
        PROCESS_MODE = PROCESS_MODE_SERVER
    }

    fun getOrientation(context: Context, photoUri: Uri): Int
    {
        /* it's on the external media. */
        val cursor: Cursor? = context.getContentResolver().query(
            photoUri,
            arrayOf<String>(MediaStore.Images.ImageColumns.ORIENTATION),
            null,
            null,
            null
        )
        if (cursor == null || cursor.getCount() != 1) {
            return -1;  //Assuming it was taken portrait
        }

        cursor.moveToFirst();
        return cursor.getInt(0);
    }

    fun rotateImage(uri: Uri, angle: Float) {
        try {
            var bitmap: Bitmap? = null
            if (Build.VERSION.SDK_INT < 28) {
                bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            } else {
                val source = ImageDecoder.createSource(contentResolver, uri)
                bitmap = ImageDecoder.decodeBitmap(source)
            }

            val matrix = Matrix()
            matrix.postRotate(angle)
            val rotatedBitmap = bitmap?.let{
                Bitmap.createBitmap(bitmap, 0, 0, it.width, bitmap.height, matrix, true) }

            bindingClass.photo.setImageBitmap(rotatedBitmap)
        } catch (e: Exception) {
            bindingClass.TextHint.text = "Ошибка при повороте"
            Log.d("rotetion", " faaaail_______________")
        }
    }

    fun onClickCamera(view: View) {
        try {
            val values = ContentValues(1)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            srcUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
            Log.d("URI", srcUri.toString())
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, srcUri)
            startActivityForResult(intent, IMAGE_CAPTURE_CODE)
        } catch (e: IOException) {
            Toast.makeText(this@MainActivity, "Could not create file!", Toast.LENGTH_SHORT).show()
        }
    }

    fun onClickGallery(view: View) {
        try {
            val galleryIntent = Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(galleryIntent, IMAGE_GALLERY_CODE)
        } catch (e: IOException) {
            Toast.makeText(this@MainActivity, "Could not choose file!", Toast.LENGTH_SHORT).show()
        }
    }

    fun onClickManual(view: View) {
        val intent = Intent(this, InstructionActivity::class.java)
        startActivity(intent)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        if (resultCode == RESULT_OK) {
            if (requestCode == IMAGE_GALLERY_CODE) {
                if (data != null) {
                    srcUri = data!!.data
                }
            }
            bindingClass.photo.setImageURI(srcUri)

            photoOrient = getOrientation(this, srcUri!!)

            Log.d("size", getOrientation(this, srcUri!!).toString())

            if (PROCESS_MODE == PROCESS_MODE_SERVER) {
                serverMode()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun serverMode() {
        val thread = Thread(Runnable {
            try {
                SC = SC_UPLOAD_ON_SERVER
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
                nameServ = "PHOTO_" + timeStamp + ".jpg"
                nameRes = "PHOTO_" + timeStamp + "_res.png"

                bindingClass.TextHint.setText("Фото отправляется на сервер")

                uploadImageToServer(server, username, password, srcUri, nameServ)
                Log.d("thread", "_________end server________")

                SC = SC_PROCESSED_ON_SERVER
                this@MainActivity.runOnUiThread(java.lang.Runnable {
                    bindingClass.TextHint.setText("Обработка")
                })
                checkFileExist(server, username, password, nameRes)

                SC = SC_UPLOAD_FROM_SERVER
                this@MainActivity.runOnUiThread(java.lang.Runnable {
                    bindingClass.TextHint.setText("Загружаем фото")
                })
                uploadImageFromServer(server, username, password, nameRes, nameGet)

                this@MainActivity.runOnUiThread(java.lang.Runnable {
                    SC = SC_READY
                    bindingClass.photo.setImageURI(dstUri)
                    var res_orient = getOrientation(this, dstUri!!)
                    Log.d("rotation", res_orient.toString())
                    if (res_orient != photoOrient){
                        rotateImage(dstUri!!, (photoOrient - res_orient).toFloat())
                    }
                    bindingClass.TextHint.setText("Готово")
                })
            } catch (e: Exception) {
                e.printStackTrace()
                bindingClass.TextHint.setText("Произошла ошибка")
            }
        })
        thread.start()
    }

    private fun uploadImageToServer(
        server: String,
        username: String,
        password: String,
        uri: Uri?,
        name: String
    ) { //
        Log.d("time", "login")
        val jsch = JSch()
        val session = jsch.getSession(username, server, 22)

        session.setPassword(password)

        session.setConfig("StrictHostKeyChecking", "no")

        session.connect()

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect()

        Log.d("time", "ready")

        channel.cd("smd/images")

        val inputStream = srcUri?.let { getContentResolver().openInputStream(it) }

        val outputStream = channel.put(inputStream, name)

        channel.disconnect()

        val outputBuffer = StringBuilder()
        val channelE = session.openChannel("exec")

//        val commands = arrayOf("cd smd && pipenv run python3 main.py eval-image --image_name=$name")

        (channelE as ChannelExec).setCommand("cd smd && pipenv run python3 main.py eval-image --image_name=" + name.toString())
        channelE.connect()

        channelE.disconnect()
        session.disconnect()
        Log.d("serv", "done")
    }

    fun checkFileExist(
        server: String,
        username: String,
        password: String,
        nameOut: String
    ) {
        val jsch = JSch()
        val session = jsch.getSession(username, server, 22)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect()

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect()
        channel.cd("smd/images")

        Log.d("serv", "trying to find file - " + nameOut)

        var isFileExists = false

        while (!isFileExists) {
            isFileExists = try {
                val attrs = channel.lstat(nameOut)
                !attrs.isDir
            } catch (e: Exception) {
                false
            }
        }

        Log.d("serv", "file - " + nameOut + " found")

        channel.disconnect()
        session.disconnect()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun uploadImageFromServer(
        server: String, username: String, password: String,
        nameIn: String, nameOut: String
    ) {
        val jsch = JSch()
        val session = jsch.getSession(username, server, 22)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect()

        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect()
        channel.cd("smd/images")

        Log.d("serv", nameIn + " " + nameOut)

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nameIn)
        }
        dstUri = applicationContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        Log.d("serv", dstUri.toString())

        val inputStream = dstUri?.let { getContentResolver().openOutputStream(it) }

        val outputStream = channel.get(nameIn, inputStream)

        Log.d("size", getOrientation(this, dstUri!!).toString())

        channel.disconnect()
        session.disconnect()
        Log.d("serv", "done")
    }
}