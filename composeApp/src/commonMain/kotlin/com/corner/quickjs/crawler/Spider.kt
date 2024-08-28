package com.corner.quickjs.crawler

import com.corner.catvodcore.util.Asset
import com.corner.catvodcore.util.Json
import com.corner.catvodcore.util.Utils.decode
import com.corner.quickjs.bean.Res
import com.corner.quickjs.method.Async
import com.corner.quickjs.method.Global
import com.corner.quickjs.utils.JSUtil
import com.corner.quickjs.utils.Module
import org.json.JSONArray
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.*

class Spider(private val key: String, private val api: String, private val loader: URLClassLoader?) :
    com.github.catvod.crawler.Spider() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cat = false
    private var ctx: Context? = null
    private var ScriptableObject: ScriptableObject? = null
    private val log: Logger = LoggerFactory.getLogger(Spider::class.java)

    init {
        initializeJS()
//        ctx.f
    }

    private fun submit(runnable: Runnable) {
        executor.submit(runnable)
    }

    private fun <T> submit(callable: Callable<T>): Future<T> {
        return executor.submit(callable)
    }

    private fun call(func: String, vararg args: Any): Any {
        //return executor.submit((Function.call(ScriptableObject, func, args))).get();
        try {
            return CompletableFuture.supplyAsync(
                { Async.run(ScriptableObject!!, func, args as Array<Any>) }, executor
            ).join().get() ?: Any()
        } catch (e: Exception) {
            log.error("call 调用错误", e)
            return Any()
        }
    }

    override fun init(extend: String?) {
        try {
            if (cat) call("init", submit<ScriptableObject> { cfg(extend) }.get())
            else call("init", (if (Json.valid(extend)) ctx!!.parse(extend) else extend)!!)
        } catch (e: Exception) {
            log.error("初始化错误", e)
            //            throw new RuntimeException(e);
        }
    }

    override fun homeContent(filter: Boolean): String {
        return call("home", filter) as String
    }

    override fun homeVideoContent(): String {
        return call("homeVod") as String
    }

    override fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>): String {
        try {
            val obj = submit<ScriptableObject> { JSUtil.toObj(ctx, extend) }.get()
            return call("category", tid, pg, filter, obj) as String
        } catch (e: Exception) {
            log.error("cate错误", e)
            //            throw new RuntimeException(e);
            return ""
        }
    }

    override fun detailContent(ids: List<String?>?): String {
        return call("detail", ids!![0]!!) as String
    }

    override fun searchContent(key: String?, quick: Boolean): String {
        return call("search", key!!, quick) as String
    }

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String {
        return call("search", key!!, quick, pg!!) as String
    }

    override fun playerContent(flag: String?, id: String?, vipFlags: List<String?>?): String {
        var array: JSArray? = null
        try {
            array = submit<JSArray> { JSUtil.toArray(ctx, vipFlags) }.get()
        } catch (e: Exception) {
            log.error("playContent 错误", e)
        }
        return call("play", flag!!, id!!, array!!) as String
    }

    override fun manualVideoCheck(): Boolean {
        return call("sniffer") as Boolean
    }

    override fun isVideoFormat(url: String?): Boolean {
        return call("isVideo", url!!) as Boolean
    }

    override fun proxyLocal(params: Map<String?, String?>?): Array<Any>? {
        try {
            return if ("catvod" == params!!["from"]) proxy2(params)
            else submit(Callable<Array<Any>?> { proxy1(params) }).get()
        } catch (e: Exception) {
            log.error("proxyLocal 错误", e)
        }
        return arrayOf()
    }

    override fun destroy() {
        submit {
            executor.shutdownNow()
//            ctx!!.destroy()
        }
    }

    @Throws(Exception::class)
    private fun initializeJS() {
        submit<Any?> {
            if (ctx == null) createCtx()
            if (loader != null) createLoader()
            createObj()
            null
        }.get()
    }

    private fun createCtx() {
        ctx = Context.enter()
        ScriptableObject = ctx!!.initStandardObjects()
        ctx!!.apply {
//            setConsole(Console())
            evaluateString(ScriptableObject, Asset.read("js/lib/http.js"), "http", 1, null)
            Global.create(ctx!!, executor).setProperty()
//            getGlobalObject().setProperty("local", Local::class.java)
//            setModuleLoader(object : BytecodeModuleLoader() {
//                override fun moduleNormalizeName(baseModuleName: String, moduleName: String): String {
//                    return convert(baseModuleName, moduleName)
//                }
//
//                override fun getModuleBytecode(moduleName: String): ByteArray {
//                    val content = Module.get().fetch(moduleName)
//                    return if (content.startsWith("//bb")) Module.get().bb(content) else compileModule(
//                        content,
//                        moduleName
//                    )
//                }
//            })
        }
    }

    private fun createLoader() {
        try {
            val obj = ctx!!.createNewScriptableObject()
            val clz = loader!!.loadClass("com.github.catvod.js.Method")
            val classes = clz.declaredClasses
            ctx!!.globalObject.setProperty("jsapi", obj)
            if (classes.size == 0) invokeSingle(clz, obj)
            if (classes.size >= 1) invokeMultiple(clz, obj)
        } catch (e: Throwable) {
            log.error("createLoader 错误", e)
        }
    }

    @Throws(Throwable::class)
    private fun invokeSingle(clz: Class<*>, jsObj: ScriptableObject) {
        invoke(clz, jsObj, clz.getDeclaredConstructor(QuickJSContext::class.java).newInstance(ctx))
    }

    @Throws(Throwable::class)
    private fun invokeMultiple(clz: Class<*>, jsObj: ScriptableObject) {
        for (subClz in clz.declaredClasses) {
            val javaObj = subClz.getDeclaredConstructor(clz).newInstance(
                clz.getDeclaredConstructor(
                    QuickJSContext::class.java
                ).newInstance(ctx)
            )
            val subObj = ctx!!.createNewScriptableObject()
            invoke(subClz, subObj, javaObj)
            jsObj.setProperty(subClz.simpleName, subObj)
        }
    }

    private fun invoke(clz: Class<*>, jsObj: ScriptableObject, javaObj: Any) {
        for (method in clz.methods) {
            if (!method.isAnnotationPresent(JSMethod::class.java)) continue
            invoke(jsObj, method, javaObj)
        }
    }

    private fun invoke(jsObj: ScriptableObject, method: Method, javaObj: Any) {
        jsObj.setProperty(method.name) { args: Array<Any?> ->
            try {
                return@setProperty method.invoke(javaObj, *args)
            } catch (e: Throwable) {
                return@setProperty null
            }
        }
    }

    private fun createObj() {
        val jsEval = "__jsEvalReturn"
        val spider = "__JS_SPIDER__"
        val global = "globalThis.$spider"
        val content = Module.get().fetch(api)
        if (content.startsWith("//bb")) ctx!!.execute(Module.get().bb(content))
        else ctx!!.evaluateModule(content.replace(spider, global), api)
        ctx!!.evaluateModule(String.format(Asset.read("js/lib/spider.js"), api))
        if (content.startsWith("//bb") || content.contains(jsEval)) cat = true
        ScriptableObject = ctx!!.getProperty(ctx!!.globalObject, spider) as ScriptableObject
    }

    private fun cfg(ext: String?): ScriptableObject {
        val cfg = ctx!!.createNewScriptableObject()
        cfg.setProperty("stype", 3)
        cfg.setProperty("skey", key)
        if (Json.invalid(ext)) cfg.setProperty("ext", ext)
        else cfg.setProperty("ext", ctx!!.parse(ext) as ScriptableObject)
        return cfg
    }

    private fun proxy1(params: Map<String?, String?>?): Array<Any> {
        val `object` = JSUtil.toObj(ctx, params)
        val array = JSONArray((ScriptableObject!!.getJSFunction("proxy").call(`object`) as JSArray).stringify())
        val headers = if (array.length() > 3) Json.toMap(array.optString(3)) else null
        val base64 = array.length() > 4 && array.optInt(4) == 1
        val result = arrayOf<Any>(
            array.optInt(0),
            array.optString(1),
            getStream(array.opt(2), base64),
            headers ?: mapOf<String, String>()
        )
        return result
    }

    @Throws(Exception::class)
    private fun proxy2(params: Map<String?, String?>?): Array<Any> {
        val url = params!!["url"]
        val header = params["header"]
        val array = submit<JSArray> {
            JSUtil.toArray(ctx, Arrays.asList(*url!!.split("/".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()))
        }.get()
        val `object` = submit<Any> { ctx!!.parse(header) }.get()
        val json = call("proxy", array, `object`) as String
        val res = Res.objectFrom(json)
        val result = arrayOf<Any>(res.code, res.contentType, res.stream)
        return result
    }

    private fun getStream(o: Any, base64: Boolean): ByteArrayInputStream {
        if (o is JSONArray) {
            val a = o
            val bytes = ByteArray(a.length())
            for (i in 0 until a.length()) bytes[i] = a.optInt(i).toByte()
            return ByteArrayInputStream(bytes)
        } else {
            var content = o.toString()
            if (base64 && content.contains("base64,")) content =
                content.split("base64,".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()[1]
            return ByteArrayInputStream(if (base64) decode(content) else content.toByteArray())
        }
    }

}
