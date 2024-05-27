# AdvanReader-10 Java Examples

&#8658; Please check our wiki: [https://wiki.keonn.com/rfid-components/advanreader-10-1-port/advanreader-m10-java-examples](https://wiki.keonn.com/rfid-components/advanreader-10-1-port/advanreader-m10-java-examples)

These examples use a combination of ThingMagic's Java SDK and propietary Keonn libraries to show how to implement some functionalities with AdvanReader-10 series devices.

* **ADRDFastEncoding:** Example showing several ways of writing EPCs with and without TID filter
* **ADRD_M1_10Asynch:** Continuously reads from the reader using only one antenna.
* **ADRDMQTTPublish:** Example that sends all read data to an MQTT server using eclipse paho library
* **ADRDRabittMQPublish:** Example that sends all read data to an MQTT server using rabbitmq library

## How to run the examples:

Run the appropiate command line command listed below from the example's root folder.

### Before starting please discover what port is created when connecting the reader to the usb port as the values listed here are the default ones

In linux is the default port is the created when no other usb device is connected to the system.

#### Windows

**32 bits**
```PowerShell
java -classpath bin;lib\slf4j-api-1.6.1.jar;lib\slf4j-simple-1.6.1.jar;lib\keonn-util.jar;lib\keonn-adrd.jar -Dgnu.io.rxtx.SerialPorts=COM10 -Djava.library.path=./native-lib/win-x86 com.keonn.adrd.ADRD_M1_10Asynch eapi://COM10
```

**64 bits** 
```PowerShell
java -classpath bin;lib\slf4j-api-1.6.1.jar;lib\slf4j-simple-1.6.1.jar;lib\keonn-util.jar;lib\keonn-adrd.jar -Dgnu.io.rxtx.SerialPorts=COM10 -Djava.library.path=./native-lib/win-amd64 com.keonn.adrd.ADRD_M1_10Asynch eapi://COM10
```

#### Linux

**x86 (32 bits)**
```sh
java -classpath bin:lib/slf4j-api-1.6.1.jar:lib/slf4j-simple-1.6.1.jar:lib/keonn-util.jar:lib/keonn-adrd.jar -Dgnu.io.rxtx.SerialPorts=/dev/ttyUSB0 -Djava.library.path=./native-lib/linux-x86 com.keonn.adrd.ADRD_M1_10Asynch eapi:///dev/ttyUSB0
```

**x64 (64 bits)**
```sh
java -classpath bin:lib/slf4j-api-1.6.1.jar:lib/slf4j-simple-1.6.1.jar:lib/keonn-util.jar:lib/keonn-adrd.jar -Dgnu.io.rxtx.SerialPorts=/dev/ttyUSB0 -Djava.library.path=./native-lib/linux-amd64 com.keonn.adrd.ADRD_M1_10Asynch eapi:///dev/ttyUSB0
```

**ARM el**
```sh
java -classpath bin:lib/slf4j-api-1.6.1.jar:lib/slf4j-simple-1.6.1.jar:lib/keonn-util.jar:lib/keonn-adrd.jar -Dgnu.io.rxtx.SerialPorts=/dev/ttyUSB0 -Djava.library.path=./native-lib/linux-arm com.keonn.adrd.ADRD_M1_10Asynch eapi:///dev/ttyUSB0
```

**ARM hf**
```sh
java -classpath bin:lib/slf4j-api-1.6.1.jar:lib/slf4j-simple-1.6.1.jar:lib/keonn-util.jar:lib/keonn-adrd.jar -Dgnu.io.rxtx.SerialPorts=/dev/ttyUSB0 -Djava.library.path=./native-lib/linux-armhf com.keonn.adrd.ADRD_M1_10Asynch eapi:///dev/ttyUSB0
```


#### MacOS X (UNTESTED)
```sh
java -classpath bin:lib/slf4j-api-1.6.1.jar:lib/slf4j-simple-1.6.1.jar:lib/keonn-util.jar:lib/keonn-adrd.jar -Djava.library.path=./native-lib/macosx com.keonn.adrd.ADRD_M1_10Asynch eapi:///dev/tty.usbserial-A5U2GDO
```
