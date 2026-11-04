package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIntentRouterTest {
    @Test fun routesDeviceStatusWithoutModel() {
        assertEquals("device_vitals", DeviceIntentRouter.route("what is my battery and free RAM")?.name)
        assertEquals("device_vitals", DeviceIntentRouter.route("show storage and uptime")?.name)
        assertEquals("device_vitals", DeviceIntentRouter.route("battery")?.name)
        assertEquals("device_vitals", DeviceIntentRouter.route("check phone battery")?.name)
    }

    @Test fun routesAlarmAndTimerArguments() {
        val alarm = DeviceIntentRouter.route("set an alarm for 7:30 pm")
        assertEquals("set_alarm", alarm?.name)
        assertEquals(19, alarm?.arguments?.optInt("hour"))
        assertEquals(30, alarm?.arguments?.optInt("minute"))
        val timer = DeviceIntentRouter.route("set timer for 5 minutes")
        assertEquals("set_timer", timer?.name)
        assertEquals(300, timer?.arguments?.optInt("seconds"))
    }

    @Test fun routesWeatherNewsAndSearch() {
        assertEquals("get_weather", DeviceIntentRouter.route("weather in Miami")?.name)
        assertEquals("get_news", DeviceIntentRouter.route("what is the latest news about Android")?.name)
        assertEquals("get_news", DeviceIntentRouter.route("what is current news")?.name)
        assertEquals("web_search", DeviceIntentRouter.route("what is the latest about bitcoin")?.name)
        assertEquals("web_search", DeviceIntentRouter.route("search online for Android 11 notification access")?.name)
        assertEquals("web_search", DeviceIntentRouter.route("today stock price")?.name)
        assertEquals("web_search", DeviceIntentRouter.route("today stock price trending in world")?.name)
        assertEquals("web_search", DeviceIntentRouter.route("trending in world")?.name)
    }

    @Test fun routesDialerAndSettings() {
        assertEquals("make_call", DeviceIntentRouter.route("Open the phone dialer")?.name)
        assertEquals("make_call", DeviceIntentRouter.route("open dialer")?.name)
        assertEquals("open_settings", DeviceIntentRouter.route("Open device battery settings")?.name)
        assertEquals("open_settings", DeviceIntentRouter.route("Open phone battery settings")?.name)
    }

    @Test fun routesNotificationRequestsButNotUnrelatedChat() {
        assertEquals("read_notifications", DeviceIntentRouter.route("read my notifications")?.name)
        assertEquals("open_notification_access", DeviceIntentRouter.route("open notification access")?.name)
        assertNull(DeviceIntentRouter.route("Explain how Android notifications work"))
        assertNull(DeviceIntentRouter.route("Tell me a story about the weather"))
        assertNull(DeviceIntentRouter.route("Explain how batteries work"))
        assertNull(DeviceIntentRouter.route("Write a python script to reverse a string"))
    }
}
