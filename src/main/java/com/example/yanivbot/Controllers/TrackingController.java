package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.DeliveryOrder;
import com.example.yanivbot.Entities.TaxiOrder;
import com.example.yanivbot.Models.DeliveryStatus;
import com.example.yanivbot.Models.TaxiOrderStatus;
import com.example.yanivbot.Repositories.DeliveryOrderRepository;
import com.example.yanivbot.Repositories.TaxiOrderRepository;
import com.example.yanivbot.Services.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
public class TrackingController {

    private static final Logger logger = LoggerFactory.getLogger(TrackingController.class);

    private final TaxiOrderRepository taxiOrderRepo;
    private final DeliveryOrderRepository deliveryOrderRepo;
    private final DriverService driverService;

    public TrackingController(TaxiOrderRepository taxiOrderRepo,
                              DeliveryOrderRepository deliveryOrderRepo,
                              DriverService driverService) {
        this.taxiOrderRepo = taxiOrderRepo;
        this.deliveryOrderRepo = deliveryOrderRepo;
        this.driverService = driverService;
    }

    @GetMapping(value = "/track/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> trackingPage(@PathVariable String token) {
        boolean exists = taxiOrderRepo.findByTrackingToken(token).isPresent()
                || deliveryOrderRepo.findByTrackingToken(token).isPresent();

        if (!exists) {
            return ResponseEntity.notFound().build();
        }

        String html = """
                <!DOCTYPE html>
                <html lang="he" dir="rtl">
                <head>
                  <meta charset="UTF-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                  <title>מעקב נהג | Movez</title>
                  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
                  <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { font-family: 'Segoe UI', Arial, sans-serif; background: #f4f6f9; display: flex; flex-direction: column; height: 100vh; }
                    #header { background: #1a73e8; color: white; padding: 14px 20px; text-align: center; font-size: 18px; font-weight: bold; letter-spacing: 0.5px; }
                    #status-bar { background: white; text-align: center; padding: 10px; font-size: 14px; color: #555; border-bottom: 1px solid #e0e0e0; }
                    #map { flex: 1; }
                    #completed-overlay {
                      display: none;
                      position: fixed; top: 0; left: 0; right: 0; bottom: 0;
                      background: rgba(255,255,255,0.95);
                      flex-direction: column; align-items: center; justify-content: center;
                      font-size: 22px; color: #2e7d32; text-align: center; gap: 12px;
                      z-index: 9999;
                    }
                    #completed-overlay .icon { font-size: 60px; }
                  </style>
                </head>
                <body>
                  <div id="header">🚗 Movez — מעקב נהג בזמן אמת</div>
                  <div id="status-bar">⏳ טוען מיקום...</div>
                  <div id="map"></div>
                  <div id="completed-overlay">
                    <div class="icon">✅</div>
                    <div>ההזמנה הושלמה בהצלחה!</div>
                    <div style="font-size:15px; color:#555;">תודה שבחרת ב-Movez 🙏</div>
                  </div>
                  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                  <script>
                    const token = '%s';
                    const map = L.map('map').setView([31.7683, 35.2137], 13);
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                      attribution: '© OpenStreetMap contributors'
                    }).addTo(map);

                    const driverIcon = L.divIcon({
                      html: '<div style="font-size:32px;">🚗</div>',
                      className: '',
                      iconAnchor: [16, 16]
                    });

                    let marker = null;
                    let firstLoad = true;
                    let pollInterval = null;

                    function poll() {
                      fetch('/api/track/' + token)
                        .then(r => r.json())
                        .then(data => {
                          if (!data.active) {
                            clearInterval(pollInterval);
                            document.getElementById('completed-overlay').style.display = 'flex';
                            return;
                          }
                          if (data.lat && data.lng) {
                            const pos = [data.lat, data.lng];
                            if (!marker) {
                              marker = L.marker(pos, { icon: driverIcon }).addTo(map);
                            } else {
                              marker.setLatLng(pos);
                            }
                            if (firstLoad) {
                              map.setView(pos, 15);
                              firstLoad = false;
                            }
                            document.getElementById('status-bar').innerText = '📍 מיקום הנהג מתעדכן כל 10 שניות';
                          }
                        })
                        .catch(() => {
                          document.getElementById('status-bar').innerText = '⚠️ לא ניתן לטעון מיקום כרגע...';
                        });
                    }

                    poll();
                    pollInterval = setInterval(poll, 10000);
                  </script>
                </body>
                </html>
                """.formatted(token);

        return ResponseEntity.ok(html);
    }

    @GetMapping("/api/track/{token}")
    public ResponseEntity<Map<String, Object>> trackingData(@PathVariable String token) {
        Map<String, Object> response = new HashMap<>();

        var taxiOrder = taxiOrderRepo.findByTrackingToken(token);
        if (taxiOrder.isPresent()) {
            TaxiOrder order = taxiOrder.get();
            boolean active = order.getStatus() == TaxiOrderStatus.ASSIGNED
                    || order.getStatus() == TaxiOrderStatus.CONFIRMED;
            response.put("active", active);
            if (active) fillDriverLocation(response, order.getDriverPhone());
            return ResponseEntity.ok(response);
        }

        var deliveryOrder = deliveryOrderRepo.findByTrackingToken(token);
        if (deliveryOrder.isPresent()) {
            DeliveryOrder order = deliveryOrder.get();
            boolean active = order.getDeliveryStatus() == DeliveryStatus.PICKED_UP;
            response.put("active", active);
            if (active) fillDriverLocation(response, order.getPickedUpBy());
            return ResponseEntity.ok(response);
        }

        return ResponseEntity.notFound().build();
    }

    private void fillDriverLocation(Map<String, Object> response, String driverPhone) {
        if (driverPhone == null) return;
        double[] loc = driverService.getDriverLocation(driverPhone);
        if (loc != null) {
            response.put("lat", loc[0]);
            response.put("lng", loc[1]);
        }
    }
}