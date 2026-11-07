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
        val wakeUp = DeviceIntentRouter.route("wake me up at 6:30 am")
        assertEquals("set_alarm", wakeUp?.name)
        assertEquals(6, wakeUp?.arguments?.optInt("hour"))
        assertEquals(30, wakeUp?.arguments?.optInt("minute"))
        val timer = DeviceIntentRouter.route("set timer for 5 minutes")
        assertEquals("set_timer", timer?.name)
        assertEquals(300, timer?.arguments?.optInt("seconds"))
    }

    @Test fun routesReminderAndAlarmControls() {
        val recurring = DeviceIntentRouter.route("remind me to drink water every 2 hours")
        assertEquals("schedule_reminder", recurring?.name)
        assertEquals("drink water", recurring?.arguments?.optString("message"))
        assertEquals(120L, recurring?.arguments?.optLong("interval_minutes"))
        assertEquals(true, recurring?.arguments?.optBoolean("recurring"))

        val delay = DeviceIntentRouter.route("remind me to take my pills in 30 minutes")
        assertEquals("schedule_reminder", delay?.name)
        assertEquals("take my pills", delay?.arguments?.optString("message"))
        assertEquals(30L, delay?.arguments?.optLong("interval_minutes"))
        assertEquals(false, delay?.arguments?.optBoolean("recurring"))

        val dismiss = DeviceIntentRouter.route("stop the alarm")
        assertEquals("dismiss_alarm", dismiss?.name)
        val dismiss2 = DeviceIntentRouter.route("dismiss alarm")
        assertEquals("dismiss_alarm", dismiss2?.name)

        val cancelReminders = DeviceIntentRouter.route("cancel all reminders")
        assertEquals("cancel_reminders", cancelReminders?.name)
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
    @Test fun routesFlexibleNaturalAlarmPhrasings() {
        val a1 = DeviceIntentRouter.route("alarm 7am")
        assertEquals("set_alarm", a1?.name)
        assertEquals(7, a1?.arguments?.optInt("hour"))
        assertEquals(0, a1?.arguments?.optInt("minute"))

        val a2 = DeviceIntentRouter.route("set alarm 8:15 pm")
        assertEquals("set_alarm", a2?.name)
        assertEquals(20, a2?.arguments?.optInt("hour"))
        assertEquals(15, a2?.arguments?.optInt("minute"))

        val a3 = DeviceIntentRouter.route("wake me 6am")
        assertEquals("set_alarm", a3?.name)
        assertEquals(6, a3?.arguments?.optInt("hour"))

        val a4 = DeviceIntentRouter.route("7am alarm")
        assertEquals("set_alarm", a4?.name)
        assertEquals(7, a4?.arguments?.optInt("hour"))

        val a5 = DeviceIntentRouter.route("add alarm for 22:00")
        assertEquals("set_alarm", a5?.name)
        assertEquals(22, a5?.arguments?.optInt("hour"))
        assertEquals(0, a5?.arguments?.optInt("minute"))
    }

    @Test fun routesFlexibleNaturalReminderPhrasings() {
        val r1 = DeviceIntentRouter.route("remind me in 15 mins to call mom")
        assertEquals("schedule_reminder", r1?.name)
        assertEquals("call mom", r1?.arguments?.optString("message"))
        assertEquals(15L, r1?.arguments?.optLong("interval_minutes"))
        assertEquals(false, r1?.arguments?.optBoolean("recurring"))

        val r2 = DeviceIntentRouter.route("remind me every 3 hours to take medicine")
        assertEquals("schedule_reminder", r2?.name)
        assertEquals("take medicine", r2?.arguments?.optString("message"))
        assertEquals(180L, r2?.arguments?.optLong("interval_minutes"))
        assertEquals(true, r2?.arguments?.optBoolean("recurring"))

        val r3 = DeviceIntentRouter.route("add reminder to check oven in 45 minutes")
        assertEquals("schedule_reminder", r3?.name)
        assertEquals("check oven", r3?.arguments?.optString("message"))
        assertEquals(45L, r3?.arguments?.optLong("interval_minutes"))
    }
}