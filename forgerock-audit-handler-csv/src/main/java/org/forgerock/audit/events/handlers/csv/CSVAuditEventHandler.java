/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.audit.events.handlers.csv;

import static org.forgerock.audit.events.AuditEventHelper.*;
import static org.forgerock.audit.util.JsonSchemaUtils.generateJsonPointers;
import static org.forgerock.audit.util.JsonValueUtils.*;
import static org.forgerock.json.resource.ResourceResponse.FIELD_CONTENT_ID;
import static org.forgerock.json.resource.Responses.newQueryResponse;
import static org.forgerock.json.resource.Responses.newResourceResponse;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.forgerock.audit.events.AuditEventHelper;
import org.forgerock.audit.events.handlers.AuditEventHandlerBase;
import org.forgerock.services.context.Context;
import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.QueryFilters;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.query.QueryFilter;
import org.forgerock.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.supercsv.cellprocessor.Optional;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.CsvMapReader;
import org.supercsv.io.ICsvMapReader;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.quote.AlwaysQuoteMode;
import org.supercsv.util.CsvContext;

/**
 * Handles AuditEvents by writing them to a CSV file.
 */
public class CSVAuditEventHandler extends AuditEventHandlerBase<CSVAuditEventHandlerConfiguration> {

    private static final Logger logger = LoggerFactory.getLogger(CSVAuditEventHandler.class);
    private static final ObjectMapper mapper;

    private Map<String, JsonValue> auditEvents;
    private String auditLogDirectory;
    private CsvPreference csvPreference;
    private final Map<String, CsvWriter> writers = new HashMap<>();
    private final Map<String, Set<String>> fieldOrderByTopic = new HashMap<>();
    /** Caches a JSON pointer for each field. */
    private final Map<String, JsonPointer> jsonPointerByField = new HashMap<>();
    /** Caches the dot notation for each field. */
    private final Map<String, String> fieldDotNotationByField = new HashMap<>();

    private CSVAuditEventHandlerConfiguration config;
    private boolean secure;
    private String keystoreFilename;
    private String keystorePassword;
    private Duration signatureInterval;

    static {
        JsonFactory jsonFactory = new JsonFactory();
        mapper = new ObjectMapper(jsonFactory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAuditEventsMetaData(final Map<String, JsonValue> auditEvents) {
        this.auditEvents = auditEvents;

        for (String topic : auditEvents.keySet()) {
            File auditLogFile = getAuditLogFile(topic);
            try {
                Set<String> fieldOrder = getFieldOrder(topic, auditEvents);
                cacheFieldsInformation(fieldOrder);
                fieldOrderByTopic.put(topic, fieldOrder);
                openWriter(topic, auditLogFile);
            } catch (IOException e) {
                logger.error("Error when creating audit file: " + auditLogFile, e);
            }
        }
    }

    /** Pre-compute field related information to be used for each event publishing. */
    private void cacheFieldsInformation(Set<String> fieldOrder) {
        for (String field : fieldOrder) {
            if (!jsonPointerByField.containsKey(field)) {
                jsonPointerByField.put(field, new JsonPointer(field));
                fieldDotNotationByField.put(field, jsonPointerToDotNotation(field));
            }
        }
    }

    /**
     * Configure the CSVAuditEventHandler.
     * {@inheritDoc}
     */
    @Override
    public void configure(final CSVAuditEventHandlerConfiguration config) throws ResourceException {
        synchronized (this) {
            cleanup();

            this.config = config;
            auditLogDirectory = config.getLogDirectory();
            logger.info("Audit logging to: {}", auditLogDirectory);

            File file = new File(auditLogDirectory);
            if (!file.isDirectory()) {
                if (file.exists()) {
                    logger.warn("Specified path is file but should be a directory: " + auditLogDirectory);
                } else {
                    if (!file.mkdirs()) {
                        logger.warn("Unable to create audit directory in the path: " + auditLogDirectory);
                    }
                }
            }
            csvPreference = createCsvPreference(config);
            final CSVAuditEventHandlerConfiguration.CsvSecurity security = config.getSecurity();
            secure = security.isEnabled();
            if (secure) {
                keystoreFilename = security.getFilename();
                keystorePassword = security.getPassword();
                Duration duration = Duration.duration(security.getSignatureInterval());
                if (duration.isZero() || duration.isUnlimited()) {
                    throw ResourceException.getException(ResourceException.NOT_SUPPORTED,
                                                         "The signature interval can't be zero nor unlimited.");
                }
                signatureInterval = duration;

            }
        }
    }

    private CsvPreference createCsvPreference(final CSVAuditEventHandlerConfiguration config) {
        CSVAuditEventHandlerConfiguration.CsvFormatting csvFormatting = config.getFormatting();
        final CsvPreference.Builder builder = new CsvPreference.Builder(csvFormatting.getQuoteChar(),
                                                                        csvFormatting.getDelimiterChar(),
                                                                        csvFormatting.getEndOfLineSymbols());

        builder.useQuoteMode(new AlwaysQuoteMode());
        return builder.build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startup() throws ResourceException {
        // TODO: Move all I/O initialization here to avoid possible interaction with another instance
        // that references the same set of files.
    }

    /** {@inheritDoc} */
    @Override
    public void shutdown() throws ResourceException {
        cleanup();
    }

    /**
     * Create a csv audit log entry.
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> publishEvent(Context context, String topic, JsonValue event) {
        try {
            checkTopic(topic);
            publishEventWithRetry(topic, event);
            return newResourceResponse(
                    event.get(ResourceResponse.FIELD_CONTENT_ID).asString(), null, event).asPromise();
        } catch (ResourceException e) {
            return e.asPromise();
        }
    }

    private void checkTopic(String topic) throws ResourceException {
        final JsonValue auditEventProperties = AuditEventHelper.getAuditEventProperties(auditEvents.get(topic));
        if (auditEventProperties == null || auditEventProperties.isNull()) {
            throw new InternalServerErrorException("No audit event properties defined for audit event: " + topic);
        }
    }

    /**
     * Publishes the provided event, and returns the writer used.
     */
    private void publishEventWithRetry(final String topic, final JsonValue event)
                    throws ResourceException {
        CsvWriter csvWriter = writers.get(topic);
        try {
            writeEvent(topic, csvWriter, event);
        } catch (IOException ex) {
            // Re-try once in case the writer stream became closed for some reason
            logger.debug("IOException during entry write, reset writer and re-try {}", ex.getMessage());
            synchronized (this) {
                resetWriter(topic, csvWriter);
                try {
                    openWriter(topic, getAuditLogFile(topic));
                } catch (IOException e) {
                    throw new BadRequestException(e);
                }
            }
            try {
                writeEvent(topic, csvWriter, event);
            } catch (IOException e) {
                throw new BadRequestException(e);
            }
        }
    }

    private CsvWriter writeEvent(final String topic, CsvWriter csvWriter, final JsonValue event)
                    throws IOException, InternalServerErrorException {
        writeEntry(topic, csvWriter, event);
        // TODO: uncomment the following statements once super-csv is released with unwrapped buffer
        // EventBufferingConfiguration bufferConfig = config.getBuffering();
        //if (!bufferConfig.isEnabled() || !bufferConfig.isAutoFlush()) {
            csvWriter.flush();
        //}
        return csvWriter;
    }

    private Set<String> getFieldOrder(final String topic, final Map<String, JsonValue> auditEvents)
            throws ResourceException {
        final Set<String> fieldOrder = new LinkedHashSet<>();
        fieldOrder.addAll(generateJsonPointers(AuditEventHelper.getAuditEventSchema(auditEvents.get(topic))));
        return fieldOrder;
    }

    private void openWriter(final String topic, File auditFile)
            throws IOException {
        final CsvWriter writer = createCsvMapWriter(auditFile, topic);
        writers.put(topic, writer);
    }

    private synchronized CsvWriter createCsvMapWriter(final File auditFile, String topic) throws IOException {
        String[] headers = buildHeaders(fieldOrderByTopic.get(topic));
        if (secure) {
            return new CsvWriter(auditFile, headers, csvPreference, config.getBuffering(), keystoreFilename,
                    keystorePassword, signatureInterval);
        } else {
            return new CsvWriter(auditFile, headers, csvPreference, config.getBuffering(), null, null, null);
        }
    }

    private ICsvMapReader createCsvMapReader(final File auditFile) throws IOException {
        CsvMapReader csvReader = new CsvMapReader(new FileReader(auditFile), csvPreference);

        if (secure) {
            return new CsvSecureMapReader(csvReader);
        } else {
            return csvReader;
        }
    }

    private String[] buildHeaders(final Collection<String> fieldOrder) {
        final String[] headers = new String[fieldOrder.size()];
        fieldOrder.toArray(headers);
        for (int i = 0; i < headers.length; i++) {
            headers[i] = jsonPointerToDotNotation(headers[i]);
        }
        return headers;
    }

    /**
     * Perform a query on the csv audit log.
     * {@inheritDoc}
     */
    @Override
    public Promise<QueryResponse, ResourceException> queryEvents(
            Context context,
            String topic,
            QueryRequest query,
            QueryResourceHandler handler) {
        try {
            for (final JsonValue value : getEntries(topic, query.getQueryFilter())) {
                handler.handleResource(newResourceResponse(value.get(FIELD_CONTENT_ID).asString(), null, value));
            }
            return newQueryResponse().asPromise();
        } catch (Exception e) {
            return new BadRequestException(e).asPromise();
        }
    }

    /**
     * Read from the csv audit log.
     * {@inheritDoc}
     */
    @Override
    public Promise<ResourceResponse, ResourceException> readEvent(Context context, String topic, String resourceId) {
        try {
            final Set<JsonValue> entry = getEntries(topic, QueryFilters.parse("/_id eq \"" + resourceId + "\""));
            if (entry.isEmpty()) {
                throw new NotFoundException(topic + " audit log not found");
            }
            final JsonValue resource = entry.iterator().next();
            return newResourceResponse(resource.get(FIELD_CONTENT_ID).asString(), null, resource).asPromise();
        } catch (ResourceException e) {
            return e.asPromise();
        } catch (IOException e) {
            return new BadRequestException(e).asPromise();
        }
    }

    private File getAuditLogFile(final String type) {
        return new File(auditLogDirectory, type + ".csv");
    }

    private void writeEntry(final String topic, final CsvWriter csvWriter, final JsonValue obj)
            throws IOException {
        Set<String> fieldOrder = fieldOrderByTopic.get(topic);
        Map<String, String> cells = new HashMap<>(fieldOrder.size());
        for (String key : fieldOrder) {
            final String value = extractFieldValue(obj, key);
            if (!value.isEmpty()) {
                cells.put(fieldDotNotationByField.get(key), value);
            }
        }
        csvWriter.writeRow(cells);
    }

    private String extractFieldValue(final JsonValue json, final String fieldName) {
        JsonValue value = json.get(jsonPointerByField.get(fieldName));
        if (value == null) {
            return "";
        } else if (value.isString()) {
            return value.asString();
        }
        return value.toString();
    }

    private void resetWriter(final String auditEventType, final CsvWriter writerToReset) {
        synchronized (writers) {
            final CsvWriter existingWriter = writers.get(auditEventType);
            if (existingWriter != null && writerToReset != null && existingWriter == writerToReset) {
                writers.remove(auditEventType);
                // attempt clean-up close
                try {
                    existingWriter.close();
                } catch (Exception ex) {
                    // Debug level as the writer is expected to potentially be invalid
                    logger.debug("File writer close in resetWriter reported failure ", ex);
                }
            }
        }
    }

    /**
     * Parser the csv file corresponding the the specified audit entry type and returns a set of matching audit entries.
     *
     * @param auditEntryType the audit log type
     * @param queryFilter the query filter to apply to the entries
     * @return  A audit log entry; null if no entry exists
     * @throws IOException If unable to get an entry from the CSV file.
     */
    private Set<JsonValue> getEntries(final String auditEntryType, QueryFilter<JsonPointer> queryFilter)
            throws IOException {
        final File auditFile = getAuditLogFile(auditEntryType);
        final Set<JsonValue> results = new HashSet<>();
        if (queryFilter == null) {
            queryFilter = QueryFilter.alwaysTrue();
        }
        if (auditFile.exists()) {
            try (ICsvMapReader reader = createCsvMapReader(auditFile)) {
                // the header elements are used to map the values to the bean (names must match)
                final String[] header = convertDotNotationToSlashes(reader.getHeader(true));
                final CellProcessor[] processors = createCellProcessors(auditEntryType, header);
                Map<String, Object> entry;
                while ((entry = reader.read(header, processors)) != null) {
                    entry = convertDotNotationToSlashes(entry);
                    final JsonValue jsonEntry = expand(entry);
                    if (queryFilter.accept(JSONVALUE_FILTER_VISITOR, jsonEntry)) {
                        results.add(jsonEntry);
                    }
                }

            }
        }
        return results;
    }

    private CellProcessor[] createCellProcessors(final String auditEntryType, final String[] headers)
            throws ResourceException {
        final List<CellProcessor> cellProcessors = new ArrayList<>();
        final JsonValue auditEvent = auditEvents.get(auditEntryType);

        for (String header: headers) {
            final String propertyType = getPropertyType(auditEvent, new JsonPointer(header));
            if ((propertyType.equals(OBJECT_TYPE) || propertyType.equals(ARRAY_TYPE))) {
                cellProcessors.add(new Optional(new ParseJsonValue()));
            } else {
                cellProcessors.add(new Optional());
            }
        }

        return cellProcessors.toArray(new CellProcessor[cellProcessors.size()]);
    }

    /**
     * CellProcessor for parsing JsonValue objects from CSV file.
     */
    public class ParseJsonValue implements CellProcessor {

        @Override
        public Object execute(final Object value, final CsvContext context) {
            JsonValue jv = null;
            // Check if value is JSON object
            if (((String) value).startsWith("{") && ((String) value).endsWith("}")) {
                try {
                    jv = new JsonValue(mapper.readValue((String) value, Map.class));
                } catch (Exception e) {
                    logger.debug("Error parsing JSON string: " + e.getMessage());
                }
            } else if (((String) value).startsWith("[") && ((String) value).endsWith("]")) {
                try {
                    jv = new JsonValue(mapper.readValue((String) value, List.class));
                } catch (Exception e) {
                    logger.debug("Error parsing JSON string: " + e.getMessage());
                }
            }
            if (jv == null) {
                return value;
            }
            return jv.getObject();
        }

    }

    private void cleanup() throws ResourceException {
        auditLogDirectory = null;
        try {
            for (CsvWriter csvWriter : writers.values()) {
                if (csvWriter != null) {
                    csvWriter.flush();
                    csvWriter.close();
                }
            }
        } catch (IOException e) {
            logger.error("Unable to close filewriters during {} cleanup", this.getClass().getName(), e);
            throw new InternalServerErrorException(
                    "Unable to close filewriters during " + this.getClass().getName() + " cleanup", e);
        }
    }

    private Map<String, Object> convertDotNotationToSlashes(final Map<String, Object> entries) {
        final Map<String, Object> newEntry = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            final String key = dotNotationToJsonPointer(entry.getKey());
            newEntry.put(key, entry.getValue());
        }
        return newEntry;
    }

    private String[] convertDotNotationToSlashes(final String[] entries) {
        String[] result = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            result[i] = dotNotationToJsonPointer(entries[i]);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<CSVAuditEventHandlerConfiguration> getConfigurationClass() {
        return CSVAuditEventHandlerConfiguration.class;
    }
}
