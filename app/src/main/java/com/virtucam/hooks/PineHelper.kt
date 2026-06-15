package com.virtucam.hooks

import android.util.Log
import de.robv.android.xposed.XposedHelpers

object PineHelper {
    open class PineCompatibleMethodHook : top.canyie.pine.callback.MethodHook() {
        override fun beforeCall(callFrame: top.canyie.pine.Pine.CallFrame) {
            try { beforeHookedMethod(callFrame) } catch (e: Throwable) { Log.e("DIAGNOSTIC_VIRTUCAM", "Hook before error", e) }
        }
        override fun afterCall(callFrame: top.canyie.pine.Pine.CallFrame) {
            try { afterHookedMethod(callFrame) } catch (e: Throwable) { Log.e("DIAGNOSTIC_VIRTUCAM", "Hook after error", e) }
        }
        open fun beforeHookedMethod(param: top.canyie.pine.Pine.CallFrame) {}
        open fun afterHookedMethod(param: top.canyie.pine.Pine.CallFrame) {}
    }

    fun hookAllMethods(clazz: Class<*>, methodName: String, hook: PineCompatibleMethodHook): Set<Any> {
        val hooks = mutableSetOf<Any>()
        try {
            clazz.declaredMethods.filter { it.name == methodName }.forEach { method ->
                try {
                    val p = top.canyie.pine.Pine.hook(method, hook)
                    if (p != null) hooks.add(p) else hooks.add(Any())
                    hooked = true
                    Log.i("DIAGNOSTIC_VIRTUCAM", "PINE HOOK REGISTRATION: Successfully injected hook on ${clazz.name}.$methodName")
                } catch (e: Throwable) {
                    Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Failed to inject hook on ${clazz.name}.$methodName", e)
                }
            }
        } catch (e: Throwable) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Reflection error querying ${clazz.name}.$methodName", e)
        }
        if (!hooked) {
            Log.w("DIAGNOSTIC_VIRTUCAM", "PINE HOOK WARNING: Target method $methodName not found or failed in ${clazz.name}")
        }
        return hooks
    }

    fun hookAllConstructors(clazz: Class<*>, hook: PineCompatibleMethodHook): Set<Any> {
        val hooks = mutableSetOf<Any>()
        try {
            clazz.declaredConstructors.forEach { constructor ->
                try {
                    val p = top.canyie.pine.Pine.hook(constructor, hook)
                    if (p != null) hooks.add(p) else hooks.add(Any())
                    Log.i("DIAGNOSTIC_VIRTUCAM", "PINE HOOK REGISTRATION: Successfully injected hook on constructor for ${clazz.name}")
                } catch (e: Throwable) {
                    Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Failed to inject hook on constructor for ${clazz.name}", e)
                }
            }
        } catch (e: Throwable) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Reflection error querying constructors for ${clazz.name}", e)
        }
        if (hooks.isEmpty()) {
            Log.w("DIAGNOSTIC_VIRTUCAM", "PINE HOOK WARNING: No constructors found or failed in ${clazz.name}")
        }
        return hooks
    }

    fun findAndHookMethod(className: String, classLoader: ClassLoader, methodName: String, vararg parameterTypesAndCallback: Any?) {
        try {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader)
            if (clazz == null) {
                Log.w("DIAGNOSTIC_VIRTUCAM", "PINE HOOK WARNING: Class $className not found for method $methodName")
                return
            }
            findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
        } catch (e: Throwable) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Reflection/Class error finding $className.$methodName", e)
        }
    }

    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg parameterTypesAndCallback: Any?) {
        try {
            val hook = parameterTypesAndCallback.last() as PineCompatibleMethodHook
            val parameterTypes = parameterTypesAndCallback.dropLast(1).toTypedArray()
            val method = XposedHelpers.findMethodExact(clazz, methodName, *parameterTypes)
            try {
                top.canyie.pine.Pine.hook(method, hook)
                Log.i("DIAGNOSTIC_VIRTUCAM", "PINE HOOK REGISTRATION: Successfully injected exact hook on ${clazz.name}.$methodName")
            } catch (e: Throwable) {
                Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Failed to inject exact hook on ${clazz.name}.$methodName", e)
            }
        } catch (e: Throwable) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Reflection/Class error finding ${clazz.name}.$methodName", e)
        }
    }

    fun findAndHookConstructor(className: String, classLoader: ClassLoader, vararg parameterTypesAndCallback: Any?) {
        try {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader)
            if (clazz == null) {
                Log.w("DIAGNOSTIC_VIRTUCAM", "PINE HOOK WARNING: Class $className not found for constructor")
                return
            }
            findAndHookConstructor(clazz, *parameterTypesAndCallback)
        } catch (e: Throwable) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Reflection/Class error finding constructor for $className", e)
        }
    }

    fun findAndHookConstructor(clazz: Class<*>, vararg parameterTypesAndCallback: Any?) {
        try {
            val hook = parameterTypesAndCallback.last() as PineCompatibleMethodHook
            val parameterTypes = parameterTypesAndCallback.dropLast(1).toTypedArray()
            val constructor = XposedHelpers.findConstructorExact(clazz, *parameterTypes)
            try {
                top.canyie.pine.Pine.hook(constructor, hook)
                Log.i("DIAGNOSTIC_VIRTUCAM", "PINE HOOK REGISTRATION: Successfully injected hook on constructor for ${clazz.name}")
            } catch (e: Throwable) {
                Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Failed to inject hook on constructor for ${clazz.name}", e)
            }
        } catch (e: Throwable) {
            Log.e("DIAGNOSTIC_VIRTUCAM", "PINE HOOK FATAL: Reflection/Class error finding constructor for ${clazz.name}", e)
        }
    }
}
