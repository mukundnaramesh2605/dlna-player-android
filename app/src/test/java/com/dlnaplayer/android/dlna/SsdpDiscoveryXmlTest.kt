package com.dlnaplayer.android.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SsdpDiscoveryXmlTest {

    private val samsungDmrXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:sec="http://www.sec.co.kr/dlna">
          <specVersion>
            <major>1</major>
            <minor>0</minor>
          </specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[Monitor] Samsung M7 32</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer>
            <modelName>Samsung M7</modelName>
            <UDN>uuid:12345678-abcd-ef01-2345-6789abcdef01</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
                <eventSubURL>/upnp/event/RenderingControl1</eventSubURL>
                <SCPDURL>/RenderingControl_1.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <controlURL>/upnp/control/ConnectionManager1</controlURL>
                <eventSubURL>/upnp/event/ConnectionManager1</eventSubURL>
                <SCPDURL>/ConnectionManager_1.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/upnp/control/AVTransport1</controlURL>
                <eventSubURL>/upnp/event/AVTransport1</eventSubURL>
                <SCPDURL>/AVTransport_1.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private val malformedXmlWithAVTransport = """
        <root>
          <friendlyName>Bedroom Samsung Monitor</friendlyName>
          <device>
            <service>
              <serviceType>AVTransport</serviceType>
              <controlURL>/upnp/control/AVTransport1</controlURL>
            </service>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun parseDeviceXml_samsungDmrEndpoint_resolvesPort9197ControlUrls() {
        val device = DeviceXmlParser.parse("http://192.168.1.150:9197/dmr", samsungDmrXml)

        assertNotNull(device)
        assertEquals("[Monitor] Samsung M7 32", device?.friendlyName)
        assertEquals("http://192.168.1.150:9197/upnp/control/AVTransport1", device?.avTransportControlUrl)
        assertEquals("http://192.168.1.150:9197/upnp/control/RenderingControl1", device?.renderingControlUrl)
        assertEquals("192.168.1.150", device?.ipAddress)
    }

    @Test
    fun parseDeviceXml_malformedXml_fallsBackToRegexSuccessfully() {
        val device = DeviceXmlParser.parse("http://192.168.1.80:9197/dmr", malformedXmlWithAVTransport)

        assertNotNull(device)
        assertEquals("Bedroom Samsung Monitor", device?.friendlyName)
        assertEquals("http://192.168.1.80:9197/upnp/control/AVTransport1", device?.avTransportControlUrl)
    }
}
