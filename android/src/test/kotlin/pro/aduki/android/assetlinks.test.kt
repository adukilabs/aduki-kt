package pro.aduki.android

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetlinksTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test fun manifestDeclaresAssetStatementsMetaData() {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        assertNotNull("meta-data bundle must be present", appInfo.metaData)

        val resourceId = appInfo.metaData.getInt("asset_statements")
        assertTrue("asset_statements must point to a resource", resourceId != 0)

        val entry = context.resources.getResourceEntryName(resourceId)
        assertEquals("asset_statements", entry)

        val value = context.getString(resourceId)
        assertNotNull("asset_statements string value must not be null", value)

        val array = JSONArray(value)
        assertEquals("asset_statements array must contain 1 entry", 1, array.length())

        val statement = array.getJSONObject(0)
        assertTrue("statement must include include key", statement.has("include"))
        assertEquals(
            "https://mail.aduki.pro/.well-known/assetlinks.json",
            statement.getString("include")
        )
    }
}
