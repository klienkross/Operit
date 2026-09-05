package com.ai.assistance.operit.integrations.http

import com.ai.assistance.operit.core.tools.SimplifiedUINode
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.UIPageResultData
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OperitPageInfoProviderTest {
    @Test
    fun `successful host page result is formatted like UINode current page`() = runTest {
        val hostResult = UIPageResultData(
            packageName = "com.example",
            activityName = ".MainActivity",
            uiElements = SimplifiedUINode(
                className = "Button",
                text = "Open",
                contentDesc = null,
                resourceId = "open_button",
                bounds = "[0,0][10,10]",
                isClickable = true,
                children = emptyList()
            )
        )
        val provider = OperitPageInfoProvider {
            ToolResult("get_page_info", true, hostResult)
        }

        val result = provider.getCurrentPageInfo()

        assertEquals(PageInfoProviderResult.Available(hostResult.toString()), result)
    }

    @Test
    fun `unsupported host page result maps to unavailable`() = runTest {
        val provider = providerFailure(
            "This operation is not supported in the standard version."
        )

        assertEquals(PageInfoProviderResult.Unavailable, provider.getCurrentPageInfo())
    }

    @Test
    fun `disabled accessibility maps to permission required`() = runTest {
        val provider = providerFailure(
            "Error getting page info: Accessibility Service is not enabled."
        )

        assertEquals(PageInfoProviderResult.PermissionRequired, provider.getCurrentPageInfo())
    }

    @Test
    fun `unexpected result type and exception map to execution error`() = runTest {
        val wrongType = OperitPageInfoProvider {
            ToolResult("get_page_info", true, StringResultData("not structured page info"))
        }
        val thrown = OperitPageInfoProvider { throw IllegalArgumentException("boom") }

        assertEquals(PageInfoProviderResult.ExecutionError, wrongType.getCurrentPageInfo())
        assertEquals(PageInfoProviderResult.ExecutionError, thrown.getCurrentPageInfo())
    }

    private fun providerFailure(error: String) = OperitPageInfoProvider {
        ToolResult(
            toolName = "get_page_info",
            success = false,
            result = StringResultData(""),
            error = error
        )
    }
}
