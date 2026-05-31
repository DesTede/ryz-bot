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
        String orderType = "taxi";
        if (deliveryOrderRepo.findByTrackingToken(token).isPresent()) {
            orderType = "delivery";
        } else if (!taxiOrderRepo.findByTrackingToken(token).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        String markerEmoji = orderType.equals("delivery") ? "🛵" : "🚕";

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
                    html, body { height: 100vh; width: 100vw; margin: 0; padding: 0; overflow: hidden; }
                    body { font-family: 'Segoe UI', Arial, sans-serif; background: #111; display: flex; flex-direction: column; }
                    #header { background: #111; border-bottom: 2px solid #f5a623; padding: 10px 20px; display: flex; justify-content: center; align-items: center; height: 55px; }
                    #header img { height: 30px; width: auto; object-fit: contain; }       
                    #status-bar { background: #1a1a1a; text-align: center; padding: 10px; font-size: 14px; color: #f5a623; border-bottom: 1px solid #2a2a2a; }
                    #map { flex: 1; width: 100vw; height: 100%; min-height: 0; }
                    #completed-overlay {
                      display: none;
                      position: fixed; top: 0; left: 0; right: 0; bottom: 0;
                      background: rgba(0,0,0,0.95);
                      flex-direction: column; align-items: center; justify-content: center;
                      font-size: 22px; color: #f5a623; text-align: center; gap: 12px;
                      z-index: 9999;
                    }
                    #completed-overlay .icon { font-size: 60px; }
                  </style>
                </head>
                <body>
                  <div id="header">
                    <img src="/images/Logo.png" alt="Movez Logo"/>
                  </div>
                  <div id="status-bar">⏳ טוען מיקום...</div>
                  <div id="map"></div>
                  <div id="completed-overlay">
                    <div class="icon">✅</div>
                    <div>ההזמנה הושלמה בהצלחה!</div>
                    <div style="font-size:15px; color:#555;">תודה שבחרת ב-Movez 🙏</div>
                  </div>
                  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                  <script>
                    const token = '[TRACKING_TOKEN]';
                    const map = L.map('map').setView([31.7683, 35.2137], 13);
                    
                    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
                        attribution: '© OpenStreetMap © CARTO',
                        subdomains: 'abcd',
                        maxZoom: 19
                      }).addTo(map);
                      
                    setTimeout(() => { map.invalidateSize(); }, 200);
               
                    const markerEmoji = '[MARKER_EMOJI]';

                    const driverIcon = L.divIcon({
                      html: '<div style="font-size:34px;filter:drop-shadow(0 0 6px #f5a623);">' + markerEmoji + '</div>',
                      className: '',
                      iconAnchor: [17, 17]
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
               \s"""
                .replace("[TRACKING_TOKEN]", token)
                .replace("[MARKER_EMOJI]", markerEmoji);

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