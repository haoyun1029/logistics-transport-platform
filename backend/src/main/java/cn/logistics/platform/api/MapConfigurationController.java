package cn.logistics.platform.api;
import cn.logistics.platform.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
/** Only browser-designated credentials; Web service credentials never enter this response. */
@RestController @RequestMapping("/api/map")
@RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN,UserRole.DISPATCHER,UserRole.DRIVER})
public class MapConfigurationController {
    private final String browserKey,securityCode;
    public MapConfigurationController(@Value("${AMAP_JS_API_KEY:}")String key,@Value("${AMAP_JS_SECURITY_CODE:}")String code){this.browserKey=key==null?"":key.trim();this.securityCode=code==null?"":code.trim();}
    @GetMapping("/configuration") public Configuration configuration(){boolean available=!browserKey.isBlank()&&!browserKey.startsWith("replace_");return new Configuration(available?"AMAP":"OSM_PREVIEW",available?browserKey:null,available&&!securityCode.isBlank()&&!securityCode.startsWith("replace_")?securityCode:null);}
    public record Configuration(String provider,String browserKey,String securityJsCode){}
}
