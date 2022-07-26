package com.example.camera2videotry7

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class CameraActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        //Called when a fragment is attached to the activity.
        savedInstanceState ?: supportFragmentManager.beginTransaction()
            //calling the camera2videoFragment instance here.
            .replace(R.id.container, Camera2VideoFragment.newInstance())
            .commit()
    }

}