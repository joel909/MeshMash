package com.example.meshmash

import com.example.meshmash.mesh.MeshReportCategory
import com.example.meshmash.mesh.RequestPriority
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshReportActionsTest {
    @Test
    fun categoryCardsHaveStableWireValues() {
        assertEquals("medical", MeshReportCategory.MEDICAL.wireValue)
        assertEquals("water", MeshReportCategory.WATER.wireValue)
        assertEquals("food", MeshReportCategory.FOOD.wireValue)
        assertEquals("shelter", MeshReportCategory.SHELTER.wireValue)
        assertEquals("missing_people", MeshReportCategory.MISSING_PEOPLE.wireValue)
        assertEquals("other", MeshReportCategory.OTHER.wireValue)
    }

    @Test
    fun prioritiesMatchDashboardValues() {
        assertEquals("NORMAL", RequestPriority.LOW.wireValue)
        assertEquals("MEDIUM", RequestPriority.MEDIUM.wireValue)
        assertEquals("HIGH", RequestPriority.HIGH.wireValue)
        assertEquals("CRITICAL", RequestPriority.CRITICAL.wireValue)
    }
}
