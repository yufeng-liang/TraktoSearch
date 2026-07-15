package com.tracktosearch

import android.app.Application
import android.content.Context
import dagger.hilt.android.testing.HiltTestApplication
import androidx.test.runner.AndroidJUnitRunner

/**
 * 自定义 TestRunner，替换为 HiltTestApplication 以支持 @HiltAndroidTest
 */
class CustomTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
