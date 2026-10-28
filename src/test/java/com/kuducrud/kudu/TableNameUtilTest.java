package com.kuducrud.kudu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableNameUtilTest {

    @Test
    void stripsImpalaPrefixBeforeResolvingDb() {
        assertEquals("itdb1", TableNameUtil.dbOf("impala::itdb1.orders_ext"));
        assertEquals("orders_ext", TableNameUtil.shortNameOf("impala::itdb1.orders_ext"));
        assertTrue(TableNameUtil.isImpalaManaged("impala::itdb1.orders_ext"));
        assertFalse(TableNameUtil.isImpalaManaged("itdb1.orders"));
    }

    @Test
    void fallsBackToDefaultDb() {
        assertEquals("default", TableNameUtil.dbOf("orders"));
        assertEquals("orders", TableNameUtil.shortNameOf("orders"));
        assertEquals("default", TableNameUtil.dbOf(""));
        assertEquals("default", TableNameUtil.dbOf(null));
    }

    @Test
    void keepsFullNameForDottedTables() {
        assertEquals("trade", TableNameUtil.dbOf("trade.orders.detail"));
        assertEquals("detail", TableNameUtil.shortNameOf("trade.orders.detail"));
    }

    @Test
    void detectsSystemTables() {
        assertTrue(TableNameUtil.isSystemTable("sys.catalog"));
        assertTrue(TableNameUtil.isSystemTable("SYS.catalog"));
        assertFalse(TableNameUtil.isSystemTable("itdb1.orders"));
    }
}
