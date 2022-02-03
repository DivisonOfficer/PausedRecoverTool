package com.dvison.pausedrecovertool

import kotlin.reflect.KSuspendFunction1

class PausedTool<Body> {
    interface PausedBodyRepositoryImpl<Body>{
        /**
         * repository에 pausedBody를 insert하는 method를 구현합니다.
         */
        suspend fun insert(body : Body)

        /**
         * repository에서 모든 pausedBody 데이터를 읽어오는 method를 구현합니다.
         */
        suspend fun getAllData() : MutableList<Body>
    }

    interface PausedBodyInterface{
        /**
         * Type에는 Class::class.java 를 toString하여 넣습니다.
         */
        val type: String

        /**
         * Gson을 통해 json으로 변환된 데이터를 넣습니다.
         */
        val json: String

        /**
         * 하나의 body class를 여러 method가 공유하는 경우, 구분을 위해 flag를 지정해줘야합니다.
         */
        val additionalFlag: Int

        /**
         * primary key, auto generate로 지정해주면됩니다.
         */
        val id: Int


        /**
         * 구현 : PausedBody를 Db에 넣는 method
         */
        suspend fun commit()
    }

    /**
     * TryCall은 요청을 보호하는 객체입니다.
     * Try~ call Catch Exception return Default Response~
     * 위 과정을 하나의 객체로 통합하여 코드 한줄로 가능하게 해줍니다.
     *
     * 사용법
     * RemoteSourceImpl의 메써드에서 요청
     * PausedTool.TryCall<{Body Class},{Response Class}>().setFailedResponse(실패시 기본 응답 클래스).setRetforitRun(service::function).run(body)
     */
    class TryCall<Body, Response>
    {
        private var failedResponse : Response? = null

        private var flag = 0

        suspend fun setFailedResponse(response : Response) : TryCall<Body,Response>
        {
            failedResponse = response
            return this
        }
        private var callMethod : (KSuspendFunction1<Body, Response>)? = null
        suspend fun setFlag(flag : Int) : TryCall<Body,Response>
        {
            this.flag = flag
            return this
        }
        suspend fun setRetrofitRun(run: KSuspendFunction1<Body, Response>) : TryCall<Body,Response>
        {
            this.callMethod = run
            return this
        }


        suspend fun run(body : Body) : Response
        {
            var response : Response
            try{
                response = callMethod!!(body)
            }
            catch(e:NullPointerException)
            {
                /**
                 * 메써드가 준비되지 않은 경우
                 */
                Log.e(this.javaClass.toString(),"Run Retrofit Method not prepared!")
                throw NullPointerException()
            }
            catch(e:Exception)
            {
                wrap(body as Any, flag).commit()
                try {
                    response = failedResponse!!
                }
                /**
                 * 실패를 처리할 Response클래스가 지정되지 않은 경우
                 */
                catch(e : NullPointerException)
                {
                    Log.e(this.javaClass.toString(),"Default Response is not Prefared!")
                    throw NullPointerException()
                }

            }
            return response
        }
    }

    /**
     * DB에 쌓인 요청들을 복구하는 툴입니다.
     * 원하는 위치에서 RecoverTool(pausedbody Db).run() 하면 됩니다.
     * addMethod를 이용하여 복구 규칙을 지정해주면됩니다.
     *
     * addMethod(bodyclass::class.java){ body : Any, flag : Int ->
     *  remoteSource.call(body as callBody)
     * }
     */
    class RecoverTool(val repository : PausedCallRepositoryImpl){

        private var methodList : MutableMap<Class<*>,suspend (Any, Int)->Unit> = mutableMapOf()

        fun addMethod(bodyClass : Class<*>, method : suspend (body : Any, flag : Int)->Unit) : RecoverTool
        {
            methodList[bodyClass] = method
            return this
        }

        suspend fun recover(wrapped: PausedBody)
        {
            val className = methodList.keys.filter{it.toString() == wrapped.type}[0]
            val body = wrapped.recover(className)
            try {

                methodList[className]!!(body, wrapped.additionalFlag)
            }
            catch(e:java.lang.Exception)
            {
                e.printStackTrace()
                Log.e(this::class.java.toString(),"${className} not in tool")
            }
        }

        fun run(){
            CoroutineScope(Dispatchers.IO).launch {
                val pausedList = repository.getAllData()
                pausedList.forEach{
                    recover(it)
                }
            }

        }

    }
    companion object {
        fun wrap(body: Any, additionalFlag : Int = 0): PausedBody {
            return PausedBody(
                    body::class.java.toString(),
                    Gson().toJson(body),
                    additionalFlag,
                    System.currentTimeMillis().toInt()
            )
        }


    }
}