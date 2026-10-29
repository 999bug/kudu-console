package com.kuducrud.web;

import com.kuducrud.model.AppVersion;
import com.kuducrud.service.AppVersionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meta")
public class VersionController {

    private final AppVersionService version;

    public VersionController(AppVersionService version) {
        this.version = version;
    }

    /** 应用版本与构建信息，供页面顶栏和部署排查使用。 */
    @GetMapping("/version")
    public AppVersion version() {
        return version.info();
    }
}
