package xyz.yophon.gopan.feature.drive

import xyz.yophon.gopan.core.prefs.DriveSort
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import xyz.yophon.gopan.core.prefs.DriveViewMode

/** 排序 / 视图偏好的状态转移(纯函数;真正落盘在 UserPreferencesRepository) */
object DrivePrefsReducer {

    /** 再点一次当前字段 = 翻转升降;换字段 = 该字段的默认方向(名称升序,大小/时间降序) */
    fun selectOrder(current: DriveSort, order: DriveSortOrder): DriveSort =
        if (current.order == order) current.copy(desc = !current.desc) else DriveSort(order, defaultDesc(order))

    fun defaultDesc(order: DriveSortOrder): Boolean = order != DriveSortOrder.NAME

    fun toggleViewMode(mode: DriveViewMode): DriveViewMode =
        if (mode == DriveViewMode.LIST) DriveViewMode.GRID else DriveViewMode.LIST
}
