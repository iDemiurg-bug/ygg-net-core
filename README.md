# Ygg-Net-Core

Прямой TUN интерфейс для Yggdrasil на Android.

## Состав

- `yggdrasil.aar` - ядро Yggdrasil (НЕ входит в репозиторий, нужно добавить вручную)
- `core-release.aar` - ваш TUN адаптер (собирается здесь)

## Требования

- Android SDK (minSdk 21, targetSdk 33)
- JDK 11 или 17
- Gradle 8.4 (используется wrapper)

## Сборка

### Локальная сборка


# Клонируем репозиторий
git clone https://github.com/username/ygg-net-core.git
cd ygg-net-core

# Собираем core модуль
./gradlew core:assembleRelease


Готовый AAR будет в `core/build/outputs/aar/core-release.aar`

### Автоматическая сборка на GitHub Actions

При каждом пуше в ветки `main` или `master` автоматически запускается сборка.
Готовый AAR можно скачать во вкладке **Actions** → выберите последний workflow → **Artifacts** → `yggdroid-core.zip`

## Использование в Kivy

### 1. Подключение AAR в buildozer.spec

В файле `buildozer.spec` вашего Kivy проекта укажите оба AAR:


android.add_src = /path/to/core-release.aar,/path/to/yggdrasil.aar


### 2. Python код

from jnius import autoclass

# Загружаем Java классы
YggdroidTun = autoclass('io.github.idemiurg.yggdroid.YggdroidTun')
Yggdrasil = autoclass('mobile.Yggdrasil')
PythonActivity = autoclass('org.kivy.android.PythonActivity')

# Получаем контекст приложения
context = PythonActivity.mActivity

# Создаём экземпляры
ygg = Yggdrasil()  # Экземпляр ядра
tun = YggdroidTun.getInstance(context)

# Связываем их (обязательно до запуска!)
tun.setYggdrasil(ygg)

# Запуск TUN интерфейса
if tun.startTun():
    print("TUN запущен успешно")
    
    # Получаем информацию
    print(f"IP адрес: {tun.getAddress()}")
    print(f"Публичный ключ: {tun.getPublicKey()}")
    print(f"Подсеть: {tun.getSubnet()}")
    
    # Добавляем пиры
    tun.addPeer("tcp://51.15.204.214:49979")
    tun.addPeer("tcp://163.172.105.72:49979")
    
    # Получаем список пиров
    peers_json = tun.getPeersJson()
    print(f"Пиры: {peers_json}")
    
    # Принудительное переподключение
    tun.retryPeers()
    
    # Остановка (когда нужно)
    # tun.stopTun()
else:
    print("Ошибка запуска TUN")


### 3. Проверка статуса

# Проверка работает ли TUN
if tun.isRunning():
    print("TUN активен")
    print(f"IP: {tun.getAddress()}")
else:
    print("TUN остановлен")


### 4. Управление пирами динамически

# Добавить пир (работает без перезапуска)
tun.addPeer("tcp://94.130.110.70:49979")

# Удалить пир
tun.removePeer("tcp://94.130.110.70:49979")


### 5. Генерация новой конфигурации


# Сгенерировать новый конфиг с ключами
new_config = tun.generateConfig()
if new_config:
    tun.saveConfig(new_config)
    print("Новый конфиг создан и сохранён")



## Версии

- **core-release.aar**: версия 1.0.0
- **Yggdrasil**: любая версия, совместимая с мобильным API (v0.5.13+)
- **Android SDK**: compileSdk 33, minSdk 21, targetSdk 33
- **Java**: совместимость с Java 11

## Лицензия

MIT

## Контакты

По вопросам использования обращайтесь в issues репозитория.
