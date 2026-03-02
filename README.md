# Music Converter - Native Android App

Bu uygulama, Android cihazlarda müzik dosyalarını dönüştürmek, etiketlerini (metadata) detaylı düzenlemek ve kütüphanenizi hızlı ve modern bir şekilde organize etmek için tasarlanmış Native bir mobil uygulamadır.

## ✨ Öne Çıkan Özellikler

-   **İki Farklı Görünüm:** Tercihinize göre Liste Görünümü (hızlı toplu işlemler için) ve Kitaplık/Grid Görünümü (büyük albüm kapaklı, görsel odaklı) arasında anında geçiş yapın.
-   **Klasör ve Çoklu Dosya Seçimi:** Şarkılarınızı tek tek seçmek yerine, tüm bir klasörü okutarak saniyeler içinde kütüphanenize içe aktarın. Hızlı Yükleme Ekranı ile büyük arşivlerde donmaları engeller.
-   **Profesyonel Tag Editörü:**
    -   **Temel Bilgiler:** Sanatçı, Albüm, Yıl, Şarkı Adı, Parça No ve Kapak Fotoğrafı yönetimi.
    -   **Detaylı Bilgiler:** Tür (Genre), Besteci (Composer), Disk No, Yorum (Comment) ve Şarkı Sözleri (Lyrics).
    -   **Akıllı Web Araması:** Bilinmeyen parçalar için tek tuşla "Kapak Ara" ve "Sözleri Ara" kısayolları (Sanatçı + Şarkı adı ile Google'da otomatik arar).
-   **Toplu Düzenleme (Batch Edit):** Seçilen tüm dosyalara tek seferde sanatçı, albüm veya yıl bilgisi atayın.
-   **Yüksek Performanslı Dönüştürme:** FFmpeg gücüyle MP3, AAC, WAV ve FLAC formatları arasında kalite kaybını minimize ederek dönüşüm yapın.
-   **Kompakt ve Modern Arayüz:** Jetpack Compose ve Material 3 kullanılarak hazırlanan, akıcı (fluid) tasarım.
-   **Depolama Alanı Optimizasyonu:** Her açılışta geçici ses dosyası önbelleğini temizleyerek telefon hafızasının gereksiz yere dolmasını engeller.
-   **Kullanıcı Deneyimi Detayları:**
    -   İşlem tamamlandığında Konfeti kutlaması 🎉
    -   Haptik (titreşim) ve sesli geri bildirimler.
    -   Bildirim çubuğunda canlı ilerleme takibi.

## 🛠 Kullanılan Teknolojiler

-   **Dil:** Kotlin
-   **UI Framework:** Jetpack Compose (Material 3)
-   **Ses İşleme:** FFmpegKit
-   **Görüntü Yükleme:** Coil
-   **Arka Plan İşlemleri:** Kotlin Coroutines & Dispatchers
-   **Veri Yönetimi:** SharedPreferences & DocumentFile API

## 🚀 Kurulum ve Çalıştırma

1.  Projenizi klonlayın.
2.  **Android Studio** ile projeyi açın.
3.  Gradle senkronizasyonunun tamamlanmasını bekleyin.
4.  Cihazınızı (veya emülatörü) bağlayın ve çalıştırın.
    -   *Not: Derleme için JDK 17 önerilir.*

---
*Geliştiren: Fatih Esen*
