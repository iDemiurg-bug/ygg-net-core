
# Ygg-Net-Core

Yggdrasil Android Core Library - ядро для интеграции Yggdrasil mesh-сети в Android приложения.

## Состав
- Нативное ядро Yggdrasil
- VPN сервис для Android
- API для управления из Python/Kivy

## Сборка

Проект использует GitHub Actions для автоматической сборки. При пуше в main или создании тега сборка запускается автоматически.

### Локальная сборка
```bash
./gradlew clean assembleRelease
