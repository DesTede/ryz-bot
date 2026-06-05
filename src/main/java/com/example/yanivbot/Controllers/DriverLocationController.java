package com.example.yanivbot.Controllers;

import com.example.yanivbot.Entities.Driver;
import com.example.yanivbot.Repositories.DriverRepository;
import com.example.yanivbot.Services.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
public class DriverLocationController {

    private static final Logger logger = LoggerFactory.getLogger(DriverLocationController.class);

    private final DriverRepository driverRepo;
    private final DriverService driverService;

    public DriverLocationController(DriverRepository driverRepo, DriverService driverService) {
        this.driverRepo = driverRepo;
        this.driverService = driverService;
    }

    /**
     * Serves the driver GPS streaming page.
     * Driver opens this on their phone browser during an active order.
     */
    @GetMapping(value = "/driver/live/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> driverLivePage(@PathVariable String token) {
        Driver driver = driverRepo.findByLocationToken(token).orElse(null);

        if (driver == null) {
            return ResponseEntity.notFound().build();
        }

        String html = """
                <!DOCTYPE html>
                <html lang="he" dir="rtl">
                <head>
                  <meta charset="UTF-8"/>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                  <meta name="mobile-web-app-capable" content="yes"/>
                  <meta name="apple-mobile-web-app-capable" content="yes"/>
                  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent"/>
                  <meta name="apple-mobile-web-app-title" content="Movez GPS"/>
                  <link rel="manifest" href="/driver/manifest.json/[DRIVER_TOKEN]"/>
                  <title>שידור מיקום | Movez</title>
                  <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                      font-family: 'Segoe UI', Arial, sans-serif;
                      background: #111;
                      display: flex;
                      flex-direction: column;
                      align-items: center;
                      justify-content: center;
                      height: 100vh;
                      gap: 24px;
                      padding: 24px;
                    }
                    #logo { display: flex; justify-content: center; align-items: center; height: 90px; }
                    #logo img { height: 90px; width: auto; object-fit: contain; }
                    #status-card {
                      background: white;
                      border-radius: 16px;
                      padding: 32px 24px;
                      text-align: center;
                      box-shadow: 0 4px 20px rgba(0,0,0,0.08);
                      width: 100%;
                      max-width: 360px;
                    }
                    #icon { font-size: 56px; margin-bottom: 12px; }
                    #status-text { font-size: 20px; font-weight: bold; color: #1a1a1a; margin-bottom: 8px; }
                    #sub-text { font-size: 14px; color: #777; line-height: 1.5; }
                    #accuracy { font-size: 13px; color: #aaa; margin-top: 12px; }
                    .warning { color: #e53935 !important; }
                    .success { color: #2e7d32 !important; }
                  </style>
                </head>
                <body>
                  <div id="logo">
                  <img src="/images/Logo.png" alt="Movez Logo"/>
                  </div>
                  <div id="status-card">
                    <div id="icon">📡</div>
                    <div id="status-text">מתחבר ל-GPS...</div>
                    <div id="sub-text">אנא המתן בזמן שאנו מאתרים את מיקומך</div>
                    <div id="accuracy"></div>
                  </div>
                  <div style="font-size:13px; color:#cc7a16; text-align:center;">
                    השאר דף זה פתוח כדי לשדר את מיקומך ללקוח
                  </div>
                
                  <audio id="bg-audio" loop src="/audio/silent.mp3" preload="auto"></audio>

                  <script>
                    const token = '[DRIVER_TOKEN]';
                    let wakeLock = null;
                    let watchId = null;
                    let intervalId = null;
                    let swIntervalId = null;

                    async function requestWakeLock() {
                      try {
                        if ('wakeLock' in navigator) {
                          wakeLock = await navigator.wakeLock.request('screen');
                        }
                      } catch (e) {
                        console.warn('WakeLock not available:', e);
                      }
                    }

                    function setStatus(icon, text, sub, isError) {
                      document.getElementById('icon').innerText = icon;
                      document.getElementById('status-text').innerText = text;
                      document.getElementById('status-text').className = isError ? 'warning' : 'success';
                      document.getElementById('sub-text').innerText = sub;
                    }

                    function sendLocation(lat, lng, accuracy) {
                      fetch('/api/driver/location/' + token, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ lat: lat, lng: lng }),
                        keepalive: true
                      })
                      .then(r => {
                        if (r.ok) {
                          setStatus('✅', 'המיקום שלך משודר', 'הלקוח יכול לעקוב אחריך בזמן אמת', false);
                          document.getElementById('accuracy').innerText = accuracy ? 'דיוק: ' + Math.round(accuracy) + ' מטר' : '';
                        } else {
                          setStatus('⚠️', 'שגיאה בשידור', 'מנסה שוב...', true);
                        }
                      })
                      .catch(() => {
                        setStatus('⚠️', 'אין חיבור לאינטרנט', 'בודק חיבור...', true);
                      });
                    }

                    function getCurrentAndSend() {
                      navigator.geolocation.getCurrentPosition(
                        (position) => {
                          sendLocation(position.coords.latitude, position.coords.longitude, position.coords.accuracy);
                        },
                        () => {},
                        { enableHighAccuracy: true, maximumAge: 10000, timeout: 10000 }
                      );
                    }

                    function startTracking() {
                      if (!navigator.geolocation) {
                        setStatus('❌', 'GPS לא נתמך', 'הדפדפן שלך אינו תומך ב-GPS', true);
                        return;
                      }
                      
                      document.addEventListener('click', function startAudio() {
                        const bgAudio = document.getElementById('bg-audio');
                        if (bgAudio) {
                          bgAudio.play().catch(() => {});
                          document.removeEventListener('click', startAudio);
                        }
                      }, { once: true });
                      
                      requestWakeLock();

                      if (watchId !== null) navigator.geolocation.clearWatch(watchId);
                      if (intervalId !== null) clearInterval(intervalId);

                      watchId = navigator.geolocation.watchPosition(
                        (position) => {
                          sendLocation(
                            position.coords.latitude,
                            position.coords.longitude,
                            position.coords.accuracy
                          );
                        },
                        (error) => {
                          switch(error.code) {
                            case error.PERMISSION_DENIED:
                              setStatus('❌', 'גישה ל-GPS נדחתה', 'אנא אפשר גישה למיקום בהגדרות הדפדפן', true);
                              break;
                            case error.POSITION_UNAVAILABLE:
                              setStatus('⚠️', 'מיקום לא זמין', 'מנסה לאתר מיקום...', true);
                              break;
                            case error.TIMEOUT:
                              setStatus('⚠️', 'זמן קצוב', 'מנסה שוב...', true);
                              break;
                          }
                        },
                        {
                          enableHighAccuracy: true,
                          maximumAge: 5000,
                          timeout: 10000
                        }
                      );

                      intervalId = setInterval(getCurrentAndSend, 15000);
                    }

                    document.addEventListener('visibilitychange', async () => {
                      if (document.visibilityState === 'visible') {
                      const bgAudio = document.getElementById('bg-audio');
                      if (bgAudio) bgAudio.play().catch(() => {});
                        await requestWakeLock();
                        startTracking();
                      }
                    });

                    startTracking();

                    if ('serviceWorker' in navigator) {
                      navigator.serviceWorker.register('/driver/sw.js', { scope: '/driver/' })
                        .then(reg => {
                          console.log('SW registered:', reg.scope);
                          if (swIntervalId !== null) clearInterval(swIntervalId);
                          swIntervalId = setInterval(() => {
                            if (navigator.serviceWorker.controller) {
                              const channel = new MessageChannel();
                              navigator.serviceWorker.controller.postMessage(
                                { type: 'KEEP_ALIVE' }, [channel.port2]
                              );
                            }
                          }, 20000);
                        })
                        .catch(err => console.warn('SW registration failed:', err));
                    }
                  </script>
                </body>
                </html>
                """.replace("[DRIVER_TOKEN]", token);
        return ResponseEntity.ok(html);
    }

    /**
     * Serves the PWA manifest so the driver page is installable on Android/iOS.
     */
    @GetMapping(value = "/driver/manifest.json/{token}", produces = "application/manifest+json")
    public ResponseEntity<String> driverManifest(@PathVariable String token) {
        String manifest = """
                {
                  "name": "Movez - שידור מיקום",
                  "short_name": "Movez GPS",
                  "start_url": "/driver/live/[TOKEN]",
                  "display": "standalone",
                  "background_color": "#111111",
                  "theme_color": "#111111",
                  "orientation": "portrait",
                  "icons": [
                    { "src": "/images/Logo.png", "sizes": "192x192", "type": "image/png" },
                    { "src": "/images/Logo.png", "sizes": "512x512", "type": "image/png" }
                  ]
                }
                """.replace("[TOKEN]", token);
        return ResponseEntity.ok(manifest);
    }

    /**
     * Serves the service worker script for PWA background location support.
     */
    @GetMapping(value = "/driver/sw.js", produces = "application/javascript")
    public ResponseEntity<String> driverServiceWorker() {
        String sw = """
                self.addEventListener('install', () => self.skipWaiting());
                self.addEventListener('activate', e => e.waitUntil(clients.claim()));

                self.addEventListener('fetch', event => {
                  const url = event.request.url;
                  if (event.request.method === 'POST' && url.includes('/api/driver/location/')) {
                    event.respondWith(
                      fetch(event.request.clone()).catch(() => new Response('', { status: 503 }))
                    );
                  }
                });

                self.addEventListener('message', event => {
                  if (event.data && event.data.type === 'KEEP_ALIVE') {
                    event.ports[0] && event.ports[0].postMessage({ type: 'ACK' });
                  }
                });
                """;
        return ResponseEntity.ok(sw);
    }

    /**
     * Receives GPS coordinates from the driver's browser page.
     * Updates the driver's location in the database.
     */
    @PostMapping("/api/driver/location/{token}")
    public ResponseEntity<Void> updateLocation(@PathVariable String token,
                                               @RequestBody Map<String, Double> body) {
        Driver driver = driverRepo.findByLocationToken(token).orElse(null);

        if (driver == null) {
            logger.warn("Location update received for unknown token: {}", token);
            return ResponseEntity.notFound().build();
        }

        Double lat = body.get("lat");
        Double lng = body.get("lng");

        if (lat == null || lng == null) {
            return ResponseEntity.badRequest().build();
        }

        driver.setLatitude(lat);
        driver.setLongitude(lng);
        driver.setLocationUpdatedAt(LocalDateTime.now());
        driverRepo.save(driver);

        logger.debug("Location updated for driver {} via browser: {}, {}", driver.getPhone(), lat, lng);
        return ResponseEntity.ok().build();
    }


}