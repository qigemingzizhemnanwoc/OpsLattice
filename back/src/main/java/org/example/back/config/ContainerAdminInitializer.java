package org.example.back.config;

import cn.hutool.crypto.digest.BCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Only activates the fresh container seed; never resets an existing password. */
@Component
@ConditionalOnProperty(name = "app.container-bootstrap", havingValue = "true")
public class ContainerAdminInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final String password;

    public ContainerAdminInitializer(JdbcTemplate jdbc,
            @Value("${ADMIN_INITIAL_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='superadmin' AND password='' AND status=0 AND is_deleted=0", Integer.class);
        if (pending == null || pending == 0) return;
        if (password.length() < 16 || password.length() > 64
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            throw new IllegalStateException("ADMIN_INITIAL_PASSWORD must contain 16-64 characters including letters and digits");
        }
        jdbc.update("UPDATE sys_user SET password=?, status=1 WHERE username='superadmin' AND password='' AND status=0 AND is_deleted=0",
                BCrypt.hashpw(password));
    }
}
