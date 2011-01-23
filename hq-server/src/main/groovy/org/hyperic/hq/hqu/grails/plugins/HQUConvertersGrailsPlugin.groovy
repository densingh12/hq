package org.hyperic.hq.hqu.grails.plugins

import grails.converters.JSON
import grails.converters.XML
import grails.util.GrailsUtil
import javax.servlet.http.HttpServletRequest
import org.codehaus.groovy.grails.web.converters.Converter
import org.codehaus.groovy.grails.web.converters.ConverterUtil
import org.codehaus.groovy.grails.web.json.JSONArray
import org.codehaus.groovy.grails.web.json.JSONObject
import org.codehaus.groovy.grails.web.converters.XMLParsingParameterCreationListener
import org.codehaus.groovy.grails.web.converters.JSONParsingParameterCreationListener
import org.springframework.core.JdkVersion
import org.springframework.validation.Errors
import org.codehaus.groovy.grails.web.converters.configuration.ConvertersConfigurationInitializer
import org.codehaus.groovy.grails.web.converters.configuration.ObjectMarshallerRegisterer
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.web.context.request.RequestContextHolder

/**
 * A plug-in that allows the obj as XML syntax
 */
class HQUConvertersGrailsPlugin {
    def version = GrailsUtil.getGrailsVersion()

    def providedArtefacts = [org.codehaus.groovy.grails.plugins.converters.codecs.JSONCodec, org.codehaus.groovy.grails.plugins.converters.codecs.XMLCodec]
    def observe = ["controllers"]

    def dependsOn = [
            HQUControllers: GrailsUtil.getGrailsVersion()
            ]


    def doWithSpringOnce = {
        xmlParsingParameterCreationListener(XMLParsingParameterCreationListener)
        jsonParsingParameterCreationListener(JSONParsingParameterCreationListener)

        jsonErrorsMarshaller(org.codehaus.groovy.grails.web.converters.marshaller.json.ValidationErrorsMarshaller)
        xmlErrorsMarshaller(org.codehaus.groovy.grails.web.converters.marshaller.xml.ValidationErrorsMarshaller)

        convertersConfigurationInitializer(ConvertersConfigurationInitializer)

        errorsXmlMarshallerRegisterer(ObjectMarshallerRegisterer) {
            marshaller = { org.codehaus.groovy.grails.web.converters.marshaller.xml.ValidationErrorsMarshaller om ->
            }
            converterClass = grails.converters.XML
        }

        errorsXmlMarshallerRegisterer(ObjectMarshallerRegisterer) {
            marshaller = { org.codehaus.groovy.grails.web.converters.marshaller.json.ValidationErrorsMarshaller om ->
            }
            converterClass = grails.converters.JSON
        }

    }

    def renderMethod = {Converter converter ->
        converter.render(delegate.response)

        // Prevent Grails from looking for a view if this method is used.
        def webRequest = RequestContextHolder.currentRequestAttributes()
        webRequest.renderView = false
    }
    def headerMethod = {String key, def value ->
        if (value) delegate.response?.setHeader(key, value.toString())
    }
    def jsonHeaderMethod = {def value ->
        def json = (value instanceof JSON || value instanceof JSONObject || value instanceof JSONArray || value instanceof String) ? value : (new JSON(value));
        if (value) delegate.response?.setHeader("X-JSON", value.toString())
    }

    def onChange = {event ->
        def mc = event.source.metaClass
        mc.render = renderMethod
        mc.header = headerMethod
        mc.jsonHeader = jsonHeaderMethod
    }

    def doWithDynamicMethods = {applicationContext ->
        ConverterUtil.setGrailsApplication(application);

        applicationContext.convertersConfigurationInitializer.initialize()

        log.debug "Applying new header and render methods to all Controllers..."
        def controllerClasses = application.controllerClasses
        for (controller in controllerClasses) {
            def mc = controller.metaClass
            mc.render = renderMethod
            mc.header = headerMethod
            mc.jsonHeader = jsonHeaderMethod
        }

        def asTypeMethod = {java.lang.Class clazz ->
            if (ConverterUtil.isConverterClass(clazz)) {
                return ConverterUtil.createConverter(clazz, delegate, applicationContext)
            } else {
                return ConverterUtil.invokeOriginalAsTypeMethod(delegate, clazz)
            }
        }

        for (dc in application.domainClasses) {
            def mc = dc.metaClass
            mc.asType = asTypeMethod
        }



        // Override GDK asType for some common Interfaces and Classes
        List targetClasses = [Errors, java.util.ArrayList, java.util.TreeSet, java.util.HashSet, java.util.List, java.util.Set, java.util.Collection, groovy.lang.GroovyObject, java.lang.Object]
        if(JdkVersion.isAtLeastJava15()) {
            targetClasses << java.lang.Enum
        }
        targetClasses.each {Class clazz ->
            MetaClassRegistry registry = GroovySystem.metaClassRegistry
            def mc = registry.getMetaClass(clazz)
            if (!(mc instanceof ExpandoMetaClass)) {
                registry.removeMetaClass(clazz)
                mc = registry.getMetaClass(clazz)
                if(!(mc instanceof ExpandoMetaClass)) {                    
                    log.warn "Unable to add Converter Functionality to Class ${className}"
                    return;
                }
            }
            log.debug "Adding Converter asType Method to Class ${clazz} [${clazz.class}] -> [${mc.class}]"
            mc.asType = asTypeMethod
            mc.initialize()
        }

        // Methods for Reading JSON/XML from Requests
        def getXMLMethod = {->
            return XML.parse((HttpServletRequest) delegate)
        }
        def getJSONMethod = {->
            return JSON.parse((HttpServletRequest) delegate)
        }
        def requestMc = GroovySystem.metaClassRegistry.getMetaClass(HttpServletRequest)
        requestMc.getXML = getXMLMethod
        requestMc.getJSON = getJSONMethod
        requestMc.initialize()

        // TODO:
        // add asType Method to XmlSlurper to unmarshalling
        // of XML Content and implement unmarshalling in JSONObject/Array...

        log.debug "Converters Plugin configured successfully"
    }
}