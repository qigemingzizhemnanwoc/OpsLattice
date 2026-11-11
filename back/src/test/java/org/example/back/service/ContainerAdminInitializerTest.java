package org.example.back.service;

import cn.hutool.crypto.digest.BCrypt;
import org.example.back.config.ContainerAdminInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class ContainerAdminInitializerTest {
    private JdbcTemplate database() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE sys_user(username VARCHAR(50), password VARCHAR(255), status INT, is_deleted INT)");
        jdbc.update("INSERT INTO sys_user VALUES ('superadmin','',0,0),('demo','',0,0)");
        return jdbc;
    }
    @Test void activatesOnlySeedAdminAndNeverResetsOnRestart() {
        var jdbc = database();
        new ContainerAdminInitializer(jdbc, "OnlyForTest-Strong123").run(null);
        String hash = jdbc.queryForObject("SELECT password FROM sys_user WHERE username='superadmin'", String.class);
        assertTrue(BCrypt.checkpw("OnlyForTest-Strong123", hash));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM sys_user WHERE username='superadmin'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM sys_user WHERE username='demo'", Integer.class));
        new ContainerAdminInitializer(jdbc, "Different-Test-45678").run(null);
        assertEquals(hash, jdbc.queryForObject("SELECT password FROM sys_user WHERE username='superadmin'", String.class));
    }
    @Test void weakOrMissingPasswordLeavesAllAccountsDisabled() {
        var jdbc = database();
        assertThrows(IllegalStateException.class, () -> new ContainerAdminInitializer(jdbc, "").run(null));
        assertThrows(IllegalStateException.class, () -> new ContainerAdminInitializer(jdbc, "short123").run(null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE status=1 OR password<>''", Integer.class));
    }
}
