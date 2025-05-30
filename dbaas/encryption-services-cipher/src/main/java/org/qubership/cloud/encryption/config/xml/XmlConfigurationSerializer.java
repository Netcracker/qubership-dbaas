package org.qubership.cloud.encryption.config.xml;

import com.google.common.base.Preconditions;
import com.google.common.io.Resources;
import org.qubership.cloud.encryption.config.ConfigurationParser;
import org.qubership.cloud.encryption.config.ConfigurationSerializer;
import org.qubership.cloud.encryption.config.EncryptionConfiguration;
import org.qubership.cloud.encryption.config.exception.IllegalConfiguration;
import org.qubership.cloud.encryption.config.xml.pojo.conf.EncryptionConfig;
import org.qubership.cloud.encryption.config.xml.pojo.crypto.CryptoSubsystemXmlConf;
import org.qubership.cloud.encryption.config.xml.pojo.keystore.*;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

public class XmlConfigurationSerializer implements ConfigurationParser, ConfigurationSerializer {
    public static final String XSD_SCHEMA = "/config/security/encryption/encryption-configuration-1_0.xsd";

    @Nonnull
    private final ConfigurationCryptoProvider cryptoProvider;

    public XmlConfigurationSerializer(@Nonnull final ConfigurationCryptoProvider cryptoProvider) {
        this.cryptoProvider = Preconditions.checkNotNull(cryptoProvider, "ConfigurationCryptoProvider can't be null");
    }

    /* (non-Javadoc)
     * @see org.qubership.cloud.encryption.config.xml.ConfiguratoinParser#loadConfiguration(java.io.File)
     */
    @Override
    @Nonnull
    public EncryptionConfiguration loadConfiguration(@Nonnull File file) {
        Preconditions.checkNotNull(file, "File for load configuration can't be null");
        try {
            Unmarshaller unmarshaller = createUnmarshaller();
            EncryptionConfiguration unmarshalledResult = (EncryptionConfiguration) unmarshaller.unmarshal(file);
            ConfigurationCryptoProvider.DecryptionResult decryptionResult =
                    cryptoProvider.decryptSecureParameters(unmarshalledResult, this);
            EncryptionConfiguration decryptedConfig = decryptionResult.getConfiguration();

            if (decryptionResult.isNotEncryptedSecureParameterPresent()) {
                saveConfiguration(file, decryptedConfig);
            }

            return decryptedConfig;
        } catch (JAXBException | SAXException e) {
            throw new IllegalConfiguration(
                    "Configuration " + file.getAbsolutePath() + " contains mistakes and can not be parse", e);
        } catch (IOException e) {
            throw new IllegalConfiguration(String.format("Error occurred while reading schema {%s}", XSD_SCHEMA), e);
        }
    }

    @Override
    public EncryptionConfiguration loadConfiguration(InputStream inputStream) {

        Preconditions.checkNotNull(inputStream, "File for load configuration can't be null");
        try {
            Unmarshaller unmarshaller = createUnmarshaller();
            EncryptionConfiguration unmarshalledResult = (EncryptionConfiguration) unmarshaller.unmarshal(inputStream);
            ConfigurationCryptoProvider.DecryptionResult decryptionResult =
                    cryptoProvider.decryptSecureParameters(unmarshalledResult, this);
            return decryptionResult.getConfiguration();

        } catch (JAXBException | SAXException e) {
            throw new IllegalConfiguration(
                    "Configuration contains mistakes and can not be parse", e);
        } catch (IOException e) {
            throw new IllegalConfiguration(String.format("Error occurred while reading schema {%s}", XSD_SCHEMA), e);
        }
    }

    private Unmarshaller createUnmarshaller() throws JAXBException, SAXException, IOException {
        Unmarshaller unmarshaller = getJaxbContext().createUnmarshaller();
        unmarshaller.setSchema(getSchema());
        
        return unmarshaller;
    }

    /* (non-Javadoc)
     * @see org.qubership.cloud.encryption.config.xml.ConfiguratoinParser#saveConfiguration(java.io.File, org.qubership.cloud.encryption.config.EncryptionConfiguration)
     */
    @Override
    public void saveConfiguration(@Nonnull File file, @Nonnull EncryptionConfiguration config) {
        try {
            Marshaller marshaller = createMarshaller();
            marshaller.marshal(cryptoProvider.cryptSecureParameters(config, this), file);
        } catch (JAXBException | SAXException e) {
            throw new IllegalConfiguration(
                    String.format("Configuration {%s} can't be marhalled to file {%s} because contain mistakes", config,
                            file.getAbsoluteFile()),
                    e);
        } catch (IOException e) {
            throw new IllegalConfiguration(String.format("Error occurred while reading schema {%s}", XSD_SCHEMA), e);
        }
    }
    
    @Override
    public void saveConfiguration(OutputStream outputStream, EncryptionConfiguration config) {
        try {
            Marshaller marshaller = createMarshaller();
            marshaller.marshal(cryptoProvider.cryptSecureParameters(config, this), outputStream);
        } catch (JAXBException | SAXException e) {
            throw new IllegalConfiguration(
                    String.format("Configuration {%s} can't be marhalled to output stream because contain mistakes!", config),
                    e);
        } catch (IOException e) {
            throw new IllegalConfiguration(String.format("Error occurred while reading schema {%s}", XSD_SCHEMA), e);
        }
    }

    private Marshaller createMarshaller() throws JAXBException, SAXException, IOException {
        JAXBContext context = getJaxbContext();
        Marshaller marshaller = context.createMarshaller();
        marshaller.setSchema(getSchema());
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        
        return marshaller;
    }

    private Schema getSchema() throws SAXException {
        URL url = Resources.getResource(this.getClass(), XSD_SCHEMA);

        final SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        return factory.newSchema(url);
    }

    private JAXBContext getJaxbContext() throws JAXBException {
        return JAXBContext.newInstance(EncryptionConfig.class, CryptoSubsystemXmlConf.class,
                AbstractKeystoreXmlConf.class, KeyStoreSubsystemXmlConf.class, EnvironmentKeystoreXmlConf.class,
                LocalKeystoreXmlConf.class, RemoteKeystoreXmlConf.class, KeyXmlConf.class);
    }
}

