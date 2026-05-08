package com.routenav;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.r2dbc.url=r2dbc:h2:mem:///routenavtest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
		"spring.r2dbc.username=sa",
		"spring.r2dbc.password=",
		"spring.sql.init.mode=always"
})
class RouteNavigatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
