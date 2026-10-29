package pro.aduki.android

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.aduki.net.http.Jmap
import pro.aduki.net.http.Responder
import pro.aduki.net.http.Vacation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VacationAndroidTest {

    private lateinit var server: MockWebServer
    private lateinit var jmap: Jmap

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val base = server.url("/").toString().trimEnd('/')
                val path = request.path.orEmpty()
                return when {
                    path == "/jmap/session" -> MockResponse().setBody(
                        """{"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:vacationresponse":{}},
                           "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
                           "apiUrl":"$base/jmap","uploadUrl":"$base/jmap/upload/{accountId}",
                           "downloadUrl":"$base/jmap/download/{accountId}/{blobId}/{name}?accept={type}"}"""
                    )
                    path == "/jmap" -> {
                        val body = JSONObject(request.body.readUtf8())
                        val calls = body.getJSONArray("methodCalls")
                        val call = calls.getJSONArray(0)
                        val name = call.getString(0)
                        val id = call.getString(2)
                        val list = JSONArray().put(
                            JSONObject()
                                .put("id", "singleton")
                                .put("isEnabled", false)
                                .put("subject", "")
                                .put("textBody", "")
                        )
                        val respArgs = JSONObject().put("list", list)
                        val responseCalls = JSONArray().put(JSONArray().put(name).put(respArgs).put(id))
                        MockResponse().setBody(JSONObject().put("methodResponses", responseCalls).toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        jmap = Jmap(OkHttpClient(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun responderFetchExecutesOnAndroidWithoutNoSuchMethodError() {
        val vacation = Responder(jmap).fetch()
        assertEquals(false, vacation.enabled)
    }
}
