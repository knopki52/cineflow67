package ru.cineflow.gateway;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Для админки: какие сервисы сейчас зарегистрированы в Eureka и сколько у каждого экземпляров. */
@RestController
public class SystemController {
    private final DiscoveryClient discovery;

    public SystemController(DiscoveryClient discovery) { this.discovery = discovery; }

    @GetMapping("/api/system/services")
    public List<Map<String, Object>> services() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : discovery.getServices()) {
            List<ServiceInstance> list = discovery.getInstances(id);
            out.add(Map.of("service", id, "instances", list.size(),
                    "hosts", list.stream().map(i -> i.getHost() + ":" + i.getPort()).toList()));
        }
        out.sort((a, b) -> ((String) a.get("service")).compareTo((String) b.get("service")));
        return out;
    }
}
