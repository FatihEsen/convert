# Music Converter - Native Android App

Bu uygulama, Android cihazlarda müzik dosyalarını dönüştürmek ve etiketlerini (metadata) düzenlemek için geliştirilmiş modern bir Native uygulamadır.

## Özellikler
- **Akıllı Etiket Ayrıştırma:** Dosya adından sanatçı, şarkı adı ve parça numarasını otomatik olarak ayıklar (Örn: `01 - Sanatçı - Şarkı.mp3`).
- **Metadata Düzenleyici:** Dönüştürmeden önce şarkı bilgilerini manuel olarak düzenleyebilme.
- **FFmpeg Gücü:** Arka planda yüksek kaliteli ses dönüşümü.
- **Jetpack Compose:** Material 3 tabanlı, şık ve modern kullanıcı arayüzü.

## Kurulum ve Çalıştırma
1. **Android Studio**'yu açın.
2. **"Open Project"** diyerek bu klasörü seçin.
3. Gradle senkronizasyonunun tamamlanmasını bekleyin.
4. Bir emülatör veya gerçek Android cihaz bağlayarak çalıştırın.

## Önemli Notlar
- Uygulama **FFmpeg Kit** kütüphanesini kullanır. Build sırasında kütüphanenin indirilmesi için internet bağlantısı gereklidir.
- Android 10 ve üzeri için dosya izinleri optimize edilmiştir.
