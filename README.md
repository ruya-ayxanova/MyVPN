# MyVPN v3 (ExpressVPN tarzı arayüz)

## Sunucu (her ülke için ayrı bir VPS, root)
1. Actions > "myvpn-server" artifact'ı veya: cd server && go mod tidy && go build -o myvpn .
2. ./myvpn -genkey                      -> PSK üretir
3. sudo MYVPN_PSK=<PSK> ./myvpn -listen :51820      (UDP 51820'yi açın)
4. Uygulamaya eklemek için bağlantı üretin:
   MYVPN_PSK=<PSK> ./myvpn -link SUNUCU_IP -name Almanya -emoji 🇩🇪
   Çıktıdaki myvpn://... bağlantısını telefona gönderip uygulamada "Sunucu ekle > bağlantı" alanına yapıştırın.

## Uygulama
- Büyük bağlan düğmesi, süre ve veri sayacı
- Sunucu listesi + canlı ping, "Akıllı konum" (en hızlıyı otomatik seçer)
- Kill switch, tüm trafik / sadece tünel ağı, uygulama bazlı ayırma (split tunneling)
- Wi-Fi <-> mobil geçişinde otomatik yeniden bağlanma
- Android Ayarlar > VPN > MyVPN > "Her zaman açık VPN" ve "VPN olmadan bağlantıları engelle" ile tam kill switch

UYARI: Protokol kendi tasarımınızdır, denetlenmemiştir; öğrenme amaçlıdır.

## Uzak liste (40 sunucuya kadar, 30 sn'de bir otomatik güncelleme)
- servers.example.json biçiminde bir JSON dosyası hazırlayın (PSK içermez).
- Gizli bir Gist veya özel erişimli bir adrese koyun ve https adresini
  Konum seç > "Uzak liste ayarları" bölümüne, ortak PSK ile birlikte girin.
- Uygulama listeyi her 30 sn'de çeker, pingleri yeniler; bağlıyken sunucu
  yanıt vermezse (40 sn) en hızlı diğer sunucuya otomatik geçer.
- Not: raw.githubusercontent.com önbellekleme yapar, değişiklikler birkaç dakika gecikebilir.
- Not: Sunucu değişiminde tünel kısa süre yeniden kurulur; sızıntı olmaması için
  Android'in "VPN olmadan bağlantıları engelle" ayarını açın.
