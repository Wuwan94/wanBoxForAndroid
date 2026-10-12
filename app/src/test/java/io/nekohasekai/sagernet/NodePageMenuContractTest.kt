// @author 雾晚
package io.nekohasekai.sagernet

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/**
 * 回归测试：节点页菜单契约 @author 雾晚
 *
 * 背景：节点页曾因移除 toolbar.inflateMenu(R.menu.add_profile_menu) 导致
 * checkOrderMenu() 中 menu.findItem() 返回 null，进而 .isChecked = true 触发 NPE，
 * 点击"节点" tab 直接闪退。
 *
 * 契约要求：
 * 1. add_profile_menu.xml 必须包含排序/布局/卡片样式等节点页必需菜单项
 * 2. ConfigurationFragment 必须 inflate 菜单（不能仅删 inflate）
 * 3. 仅隐藏 action_add（添加节点入口），保留其他功能项
 */
class NodePageMenuContractTest {

    private fun parseMenu(): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder()
            .parse(File("src/main/res/menu/add_profile_menu.xml"))
        return document.documentElement
    }

    private fun collectIds(element: Element, ids: MutableSet<String>) {
        val id = element.getAttribute("android:id")
        if (id.startsWith("@+id/")) {
            ids.add(id.removePrefix("@+id/"))
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) collectIds(child, ids)
        }
    }

    @Test fun `node page required menu items exist`() {
        val ids = mutableSetOf<String>()
        collectIds(parseMenu(), ids)

        // 排序（checkOrderMenu 必需，否则 NPE）
        assertTrue("action_order_origin missing", ids.contains("action_order_origin"))
        assertTrue("action_order_by_name missing", ids.contains("action_order_by_name"))
        assertTrue("action_order_by_delay missing", ids.contains("action_order_by_delay"))

        // 单双列布局
        assertTrue("action_layout_single missing", ids.contains("action_layout_single"))
        assertTrue("action_layout_double missing", ids.contains("action_layout_double"))

        // 卡片样式
        assertTrue("action_card_style_classic missing", ids.contains("action_card_style_classic"))
        assertTrue("action_card_style_stroke missing", ids.contains("action_card_style_stroke"))

        // 添加节点入口（应存在，供隐藏逻辑使用）
        assertTrue("action_add missing", ids.contains("action_add"))
    }

    @Test fun `configuration fragment inflates menu and hides only action_add`() {
        val source = File("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt")
            .readText()

        // 必须 inflate 菜单（不能删除，否则 checkOrderMenu NPE）
        assertTrue(
            "ConfigurationFragment must inflate add_profile_menu",
            source.contains("toolbar.inflateMenu(R.menu.add_profile_menu)")
        )

        // 必须隐藏 action_add（添加节点入口移到订阅页）
        assertTrue(
            "ConfigurationFragment must hide action_add",
            source.contains("findItem(R.id.action_add)?.isVisible = false")
        )
    }
}
