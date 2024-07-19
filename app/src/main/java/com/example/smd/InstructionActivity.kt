package com.example.smd

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View

class InstructionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instruction)
    }

    fun onClickClose(view: View) {
        finish()
    }


}