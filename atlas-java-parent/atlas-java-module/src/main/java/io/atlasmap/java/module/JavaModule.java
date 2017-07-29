/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atlasmap.java.module;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.atlasmap.api.AtlasContextFactory;
import io.atlasmap.api.AtlasConversionException;
import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.api.AtlasValidationException;
import io.atlasmap.core.AtlasModuleSupport;
import io.atlasmap.core.AtlasUtil;
import io.atlasmap.core.BaseAtlasModule;
import io.atlasmap.core.DefaultAtlasContextFactory;
import io.atlasmap.core.PathUtil;
import io.atlasmap.java.inspect.ClassHelper;
import io.atlasmap.java.inspect.ClassInspectionService;
import io.atlasmap.java.inspect.ConstructException;
import io.atlasmap.java.inspect.JavaConstructService;
import io.atlasmap.java.inspect.JdkPackages;
import io.atlasmap.java.inspect.StringUtil;
import io.atlasmap.java.v2.AtlasJavaModelFactory;
import io.atlasmap.java.v2.JavaClass;
import io.atlasmap.java.v2.JavaEnumField;
import io.atlasmap.java.v2.JavaField;
import io.atlasmap.spi.AtlasModuleDetail;
import io.atlasmap.v2.AtlasMapping;
import io.atlasmap.v2.AuditStatus;
import io.atlasmap.v2.BaseMapping;
import io.atlasmap.v2.ConstantField;
import io.atlasmap.v2.DataSource;
import io.atlasmap.v2.DataSourceType;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldType;
import io.atlasmap.v2.Mapping;
import io.atlasmap.v2.PropertyField;
import io.atlasmap.v2.SimpleField;
import io.atlasmap.v2.Validation;

// pending processOutputCollection removal
// import io.atlasmap.java.v2.JavaCollection;
// import io.atlasmap.v2.Audit;
// import io.atlasmap.v2.Collection;
// import io.atlasmap.v2.MappingType;

@AtlasModuleDetail(name = "JavaModule", uri = "atlas:java", modes = { "SOURCE", "TARGET" }, dataFormats = { "java" }, configPackages = { "io.atlasmap.java.v2" })
public class JavaModule extends BaseAtlasModule {
    private static final Logger logger = LoggerFactory.getLogger(JavaModule.class);
    public static final String DEFAULT_LIST_CLASS = "java.util.ArrayList";
    
    private ClassInspectionService javaInspectionService = null;
    private JavaConstructService javaConstructService = null;        
    
    @Override
    public void init() {
        javaInspectionService = new ClassInspectionService();
        javaInspectionService.setConversionService(getConversionService());
        setJavaInspectionService(javaInspectionService);
        
        javaConstructService = new JavaConstructService();
        javaConstructService.setConversionService(getConversionService());
        setJavaConstructService(javaConstructService);
    }

    @Override
    public void destroy() {
        javaInspectionService = null;
        javaConstructService = null;
    }

    // TODO: Support runtime class inspection
    @Override
    public void processPreInputExecution(AtlasSession atlasSession) throws AtlasException {
        if(atlasSession == null || atlasSession.getMapping() == null 
                || atlasSession.getMapping().getMappings() == null 
                || atlasSession.getMapping().getMappings().getMapping() == null) {
            logger.error("AtlasSession not properly intialized with a mapping that contains field mappings");
            return;
        }
        
        if(javaInspectionService == null) {
            javaInspectionService = new ClassInspectionService();
            javaInspectionService.setConversionService(getConversionService());
        }
        
        if(logger.isDebugEnabled()) {
            logger.debug("processPreInputExcution completed");
        }
    }
    
    @Override
    public void processPreOutputExecution(AtlasSession atlasSession) throws AtlasException {              
        if(atlasSession == null || atlasSession.getMapping() == null 
                || atlasSession.getMapping().getMappings() == null 
                || atlasSession.getMapping().getMappings().getMapping() == null) {
            logger.error("AtlasSession not properly intialized with a mapping that contains field mappings");
            return;
        }
        
        if(javaInspectionService == null) {
            javaInspectionService = new ClassInspectionService();
            javaInspectionService.setConversionService(getConversionService());
        }
        
        if(logger.isDebugEnabled()) {
            logger.debug("processPreOutputExcution completed");
        }
    }

    @Override
    public void processPreValidation(AtlasSession atlasSession) throws AtlasException {        
        if(atlasSession == null || atlasSession.getMapping() == null) {
            logger.error("Invalid session: Session and AtlasMapping must be specified");
            throw new AtlasValidationException("Invalid session");
        }
        
        JavaValidationService javaValidator = new JavaValidationService(getConversionService());
        List<Validation> javaValidations = javaValidator.validateMapping(atlasSession.getMapping());
        atlasSession.getValidations().getValidation().addAll(javaValidations);
        
        if(logger.isDebugEnabled()) {
            logger.debug("Detected " + javaValidations.size() + " java validation notices");
        }
               
        if(logger.isDebugEnabled()) {
            logger.debug("processPreValidation completed");
        }
    }
    
    @Override
    public void processInputMapping(AtlasSession session, BaseMapping baseMapping) throws AtlasException {
        for (Mapping mapping : this.generateInputMappings(session, baseMapping)) {
            if(mapping.getInputField() == null || mapping.getInputField().isEmpty()) {
                addAudit(session, null, String.format("Mapping does not contain at least one input field alias=%s desc=%s", mapping.getAlias(), mapping.getDescription()), null, AuditStatus.WARN, null);
                return;
            }
            
            for(Field field : mapping.getInputField()) {
                if(!isSupportedField(field)) {
                    addAudit(session, field.getDocId(), String.format("Unsupported input field type=%s", field.getClass().getName()), field.getPath(), AuditStatus.ERROR, null);
                    continue;
                }
            
                if(field instanceof PropertyField) {
                    processPropertyField(session, mapping, session.getAtlasContext().getContextFactory().getPropertyStrategy());
                    if(logger.isDebugEnabled()) {
                        logger.debug("Processed input propertyField sPath=" + field.getPath() + " sV=" + field.getValue() + " sT=" + field.getFieldType() + " docId: " + field.getDocId());
                    }
                    continue;
                }
            
                Object sourceObject = session.getInput();;
                if(field.getDocId() != null) {
                    sourceObject = session.getInput(field.getDocId());
                }
            
                try {
                    processInputMapping(field, sourceObject, session);
                
                    if(logger.isDebugEnabled()) {
                        logger.debug("Processed input field sPath=" + field.getPath() + " sV=" + field.getValue() + " sT=" + field.getFieldType() + " docId: " + field.getDocId());
                    }
                } catch (Exception e) {
                    addAudit(session, field.getDocId(), String.format("Unexpected error occured msg=%s", e.getMessage()), field.getPath(), AuditStatus.ERROR, null);
                    logger.error("Unexpected error occured msg=" + e.getMessage(), e);
                    throw new AtlasException(e);
                }
            }
        }
    }                    
    
    protected void processInputMapping(Field sourceField, Object source, AtlasSession session) throws Exception {
        Method getter = null;
        if((sourceField).getFieldType() == null) {
            getter = resolveGetMethod(source, sourceField, false);
            if(getter == null) {
                logger.warn("Unable to auto-detect sourceField type p=" + sourceField.getPath() + " d=" + sourceField.getDocId());
                return;
            }
            Class<?> returnType = getter.getReturnType();
            sourceField.setFieldType(getConversionService().fieldTypeFromClass(returnType));
            if(logger.isTraceEnabled()) {
                logger.trace("Auto-detected sourceField type p=" + sourceField.getPath() + " t=" + sourceField.getFieldType());
            }
        }
            
        populateSourceFieldValue(sourceField, source, getter);
    }
    
    protected void populateSourceFieldValue(Field field, Object source, Method getter) throws Exception {
        Object parentObject = source;
        PathUtil pathUtil = new PathUtil(field.getPath());        
        if (pathUtil.hasParent()) {
            parentObject = ClassHelper.parentObjectForPath(source, pathUtil, true);
        }
        getter = (getter == null) ? resolveGetMethod(parentObject, field, (parentObject != source)) : getter;
        
        Object sourceValue = null;               
        if (getter != null) {
            sourceValue = getter.invoke(parentObject);
        }
                
        // TODO: support doing parent stuff at field level vs getter
        if(sourceValue == null) {
            sourceValue = getValueFromMemberField(source, pathUtil.getLastSegment());
        }
        
        if(sourceValue != null && (getConversionService().isPrimitive(sourceValue.getClass()) || getConversionService().isBoxedPrimitive(sourceValue.getClass()))) {
            sourceValue = getConversionService().copyPrimitive(sourceValue);
        }
        
        field.setValue(sourceValue);
    }
    
    public static Object getValueFromMemberField(Object source, String fieldName) throws Exception {
        try {
            java.lang.reflect.Field reflectField = source.getClass().getField(fieldName);
            reflectField.setAccessible(true);
            return reflectField.get(source);
        } catch (NoSuchFieldException nsfe) {
            // TODO: Add audit entry
        }
        return null;
    }

    private Object initializeTargetObject(AtlasMapping atlasMapping) throws ClassNotFoundException, IllegalAccessException, InstantiationException, ConstructException {        
        String targetUri = null;
        for(DataSource ds : atlasMapping.getDataSource()) {
            if(DataSourceType.TARGET.equals(ds.getDataSourceType())) {
                targetUri = ds.getUri();
            }
        }
        
        String targetClassName = AtlasUtil.getUriParameterValue(targetUri, "className");
        JavaClass inspectClass = getJavaInspectionService().inspectClass(targetClassName);
        merge(inspectClass, atlasMapping.getMappings().getMapping());
        List<String> targetPaths = AtlasModuleSupport.listTargetPaths(atlasMapping.getMappings().getMapping());
        return getJavaConstructService().constructClass(inspectClass, targetPaths);
    }      
       
    @Override
    public void processOutputMapping(AtlasSession session, BaseMapping baseMapping) throws AtlasException {
        for (Mapping mapping : this.getOutputMappings(session, baseMapping)) {
            if(mapping.getOutputField() == null || mapping.getOutputField().isEmpty()) {
                addAudit(session, null, String.format("Mapping does not contain at least one output field alias=%s desc=%s", mapping.getAlias(), mapping.getDescription()), null, AuditStatus.ERROR, null);
                return;
            }
            
            Field outputField = mapping.getOutputField().get(0);
            
            if(!(outputField instanceof JavaField) && !(outputField instanceof JavaEnumField)) {
                addAudit(session, outputField.getDocId(), String.format("Unsupported output field type=%s", outputField.getClass().getName()), outputField.getPath(), AuditStatus.ERROR, null);                
                return;
            }
            
            try {
                DocumentJavaFieldWriter writer = (DocumentJavaFieldWriter) session.getOutput();
                if (writer == null) {
                    writer = new DocumentJavaFieldWriter();
                    session.setOutput(writer);
                }
                Object targetObject = writer.getRootObject();
                if (targetObject == null) {
                    try {
                        targetObject = initializeTargetObject(session.getMapping());
                        writer.setRootObject(targetObject);
                    } catch (Exception e) {
                        addAudit(session, outputField.getDocId(), String.format("Error initializing targetObject msg=%s", e.getMessage()), outputField.getPath(), AuditStatus.ERROR, null);                        
                        return;
                    }                
                }
                                
                OutputValueConverter valueConverter = null;
                switch(mapping.getMappingType()) {
                    case MAP: 
                        Field inputField = mapping.getInputField().get(0);      
                        valueConverter = new OutputValueConverter(inputField, session, mapping, getConversionService(), this);
                        writer.write(outputField, valueConverter);
                        break;
                    case COMBINE:
                        processCombineField(session, mapping, mapping.getInputField(), outputField);
                        SimpleField combinedField = new SimpleField();
                        combinedField.setFieldType(FieldType.STRING);
                        combinedField.setValue(outputField.getValue());
                        valueConverter = new OutputValueConverter(combinedField, session, mapping, getConversionService(), this);
                        writer.write(outputField, valueConverter);
                        break;
                    case LOOKUP:
                        Field inputFieldlkp = mapping.getInputField().get(0);      
                        valueConverter = new OutputValueConverter(inputFieldlkp, session, mapping, getConversionService(), this);
                        writer.write(outputField, valueConverter);
                        break;
                    case SEPARATE:
                        Field inputFieldsep = mapping.getInputField().get(0);
                        for(Field outputFieldsep : mapping.getOutputField()) {
                            Field separateField = processSeparateField(session, mapping, inputFieldsep, outputFieldsep);
                            if(separateField == null) {
                            continue;
                            }
                            valueConverter = new OutputValueConverter(separateField, session, mapping, getConversionService(), this);
                            writer.write(outputFieldsep, valueConverter);
                        }
                        break;
                    default: logger.error("Unsupported mappingType=%s detected", mapping.getMappingType()); return;
                }
                
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                if (e instanceof AtlasException) {
                    throw (AtlasException) e;
                }
                throw new AtlasException(e.getMessage(), e);
            }
    
            if (logger.isDebugEnabled()) {
                logger.debug("processOutputMapping completed");
            }
        }
    }
    
    /*
    private void processOutputCollection(AtlasSession session, Collection collection) throws AtlasException {        
        if(collection == null || collection.getMappings() == null || collection.getMappings().getMapping() == null || collection.getMappings().getMapping().isEmpty()) {
            if(logger.isDebugEnabled()) {
                logger.debug("Empty output collection mapping detected");
            }
            Audit audit = new Audit();
            audit.setStatus(AuditStatus.WARN);
            audit.setMessage(String.format("Collection does not contain any mapping entries alias=%s, desc=%s, skipping.", collection.getAlias(), collection.getDescription()));
            session.getAudits().getAudit().add(audit);
            return;
        }
        
        logger.debug("Processing output for collection mapping items: " + collection.getMappings().getMapping().size() + " mappings.");
        
        JavaCollection javaCollection = null;
        if(collection instanceof JavaCollection) {
            javaCollection = (JavaCollection)collection;
            // do javaCollection.getCollectionClassName() stuff
        }

        for (BaseMapping baseMapping : javaCollection.getMappings().getMapping()) {
            if (MappingType.COLLECTION.equals(baseMapping.getMappingType())) {
                throw new AtlasException("We do not support collection mappings nested inside other collection mappings: " + baseMapping + ", collection: " + collection);
            } else if (MappingType.LOOKUP.equals(baseMapping.getMappingType())) {
                throw new AtlasException("We do not support lookup mappings nested inside collection mappings: " + baseMapping + ", collection: " + collection);
            }
            Mapping mapping = (Mapping) baseMapping;
            this.processOutputMapping(session, mapping);
        }
        if(logger.isDebugEnabled()) {
            logger.debug("processOutputCollectionMapping completed");
        }        
    }
    */
        
    @Override
    public void processPostOutputExecution(AtlasSession session) throws AtlasException {
        Object output = session.getOutput();
        if (output instanceof DocumentJavaFieldWriter) {
            if (((DocumentJavaFieldWriter) output).getRootObject() != null) {
                session.setOutput(((DocumentJavaFieldWriter) output).getRootObject());
            } else {
                // TODO: handle error where rootnode on DocumentJavaFieldWriter is set to null, which should never happen.
            }
        } else {
            logger.error("DocumentJavaFieldWriter object expected for Java output data source, instead it's: " + session.getOutput());
        }
        if (logger.isDebugEnabled()) {
            logger.debug("processPostOutputExecution completed");
        }
    }    
    
    protected List<String> separateValue(AtlasSession session, String value, String delimiter) throws AtlasConversionException {
        AtlasContextFactory contextFactory = session.getAtlasContext().getContextFactory();
        if (contextFactory instanceof DefaultAtlasContextFactory) {
            return ((DefaultAtlasContextFactory) contextFactory).getSeparateStrategy().separateValue(value, delimiter,
                    null);
        } else {
            throw new AtlasConversionException("No supported SeparateStrategy found");
        }
    }
    
    protected void merge(JavaClass inspectionClass, List<BaseMapping> mappings) {
        if(inspectionClass == null || inspectionClass.getJavaFields() == null || inspectionClass.getJavaFields().getJavaField() == null) {
            return;
        }
        
        if(mappings == null || mappings.size() == 0) {
            return;
        }
        
        for(BaseMapping fm :mappings) {
            if(fm instanceof Mapping) {
                if(((Mapping)fm).getOutputField() != null) {
                    Field f = ((Mapping)fm).getOutputField().get(0);
                    if(f.getPath() != null) {
                        Field inspectField = findFieldByPath(inspectionClass, f.getPath());
                        if(inspectField != null && f instanceof JavaField && inspectField instanceof JavaField) {
                            String overrideClassName = ((JavaField)f).getClassName();
                            JavaField javaInspectField = (JavaField) inspectField;
                            // Support mapping overrides className
                            if(overrideClassName != null && !overrideClassName.equals(javaInspectField.getClassName())) {
                                javaInspectField.setClassName(overrideClassName);
                            }
                        }
                    }
                }
            }
        }
    }
    
    protected static Method resolveGetMethod(Object sourceObject, Field field, boolean objectIsParent) throws AtlasException {
        Object parentObject = sourceObject;
        PathUtil pathUtil = new PathUtil(field.getPath());
        Method getter = null;

        if (pathUtil.hasParent() && !objectIsParent) {
            parentObject = ClassHelper.parentObjectForPath(sourceObject, pathUtil, true);
        }
        
        List<Class<?>> classTree = resolveMappableClasses(parentObject.getClass());
        
        for(Class<?> clazz : classTree) {
            try {                
                if(field instanceof JavaField && ((JavaField)field).getGetMethod() != null) { 
                    getter = clazz.getMethod(((JavaField)field).getGetMethod());
                    getter.setAccessible(true);
                    return getter;
                }
            } catch (NoSuchMethodException e) {
                // no getter method specified in mapping file   
            }

            for(String m : Arrays.asList("get", "is")) {
                String getterMethod = m + capitalizeFirstLetter(pathUtil.getLastSegment());
                try {
                    getter = clazz.getMethod(getterMethod);
                    getter.setAccessible(true);
                    return getter;
                } catch (NoSuchMethodException e) {
                    // method does not exist
                }
            }
        }
        return null;
    }
    
    protected Method resolveInputSetMethod(Object sourceObject, JavaField javaField, Class<?> targetType) throws AtlasException {        
        PathUtil pathUtil = new PathUtil(javaField.getPath());
        Object parentObject = sourceObject;

        if (pathUtil.hasParent()) {
            parentObject = ClassHelper.parentObjectForPath(parentObject, pathUtil, true);
        }
        List<Class<?>> classTree = resolveMappableClasses(parentObject.getClass());
        
        for(Class<?> clazz : classTree) {
            try {
                String setterMethodName = javaField.getSetMethod();
                if(setterMethodName == null) { 
                    setterMethodName = "set" + capitalizeFirstLetter(pathUtil.getLastSegment());
                } 
                return ClassHelper.detectSetterMethod(clazz, setterMethodName, targetType);
            } catch (NoSuchMethodException e) {
                // method does not exist
            }

            // Try the boxUnboxed version
            if(getConversionService().isPrimitive(targetType) || getConversionService().isBoxedPrimitive(targetType)) {
                try {
                    String setterMethodName = javaField.getSetMethod();
                    if(setterMethodName == null) {
                        setterMethodName = "set" + capitalizeFirstLetter(pathUtil.getLastSegment());
                    }                         
                    return ClassHelper.detectSetterMethod(clazz, setterMethodName, getConversionService().boxOrUnboxPrimitive(targetType));
                } catch (NoSuchMethodException e) {
                    // method does not exist
                }
            }
        }
        
        throw new AtlasException(String.format("Unable to resolve setter for path=%s", javaField.getPath()));
    }
    
        
    public static List<Class<?>> resolveMappableClasses(Class<?> className) {        
        List<Class<?>> classTree = new ArrayList<Class<?>>();
        classTree.add(className);
        
        Class<?> superClazz = className.getSuperclass();
        while (superClazz != null) {
            if (JdkPackages.contains(superClazz.getPackage().getName())) {
//                if (logger.isDebugEnabled()) {
//                    logger.debug("Ignoring SuperClass " + superClazz.getName() + " which is a Jdk core class");
//                }
                superClazz = null;
            } else {
                classTree.add(superClazz);
                superClazz = superClazz.getSuperclass();
            }
        }
        
        // DON'T reverse.. prefer child -> parent -> grandparent
        //List<Class<?>> reverseTree = classTree.subList(0, classTree.size());
        //Collections.reverse(reverseTree);
        //return reverseTree;
        return classTree;
    }
    
    protected JavaField findFieldByPath(JavaClass javaClass, String javaPath) {
        if(javaClass == null || javaClass.getJavaFields() == null || javaClass.getJavaFields().getJavaField() == null) {
            return null;
        }
        
        for(JavaField jf : javaClass.getJavaFields().getJavaField()) {
            if(jf.getPath().equals(javaPath)) {
                return jf;
            }
            if(jf instanceof JavaClass) {
                JavaField childJavaField = findFieldByPath((JavaClass)jf, javaPath);
                if(childJavaField != null) {
                    return childJavaField;
                }
            }
        }
        
        return null;
    }
    
    public static String capitalizeFirstLetter(String sentence) {
        if (StringUtil.isEmpty(sentence)) {
            return sentence;
        }
        if (sentence.length() == 1) {
            return String.valueOf(sentence.charAt(0)).toUpperCase();
        }
        return String.valueOf(sentence.charAt(0)).toUpperCase() + sentence.substring(1);
    }
            
    public ClassInspectionService getJavaInspectionService() {
        return javaInspectionService;
    }

    public void setJavaInspectionService(ClassInspectionService javaInspectionService) {
        this.javaInspectionService = javaInspectionService;
    }

    public JavaConstructService getJavaConstructService() {
        return javaConstructService;
    }

    public void setJavaConstructService(JavaConstructService javaConstructService) {
        this.javaConstructService = javaConstructService;
    }       

    @Override
    public Boolean isSupportedField(Field field) {
        if (field instanceof JavaField) {
            return true;
        } else if (field instanceof JavaEnumField) {
            return true;
        } else if (field instanceof PropertyField) {
            return true;
        } else if (field instanceof ConstantField) {
            return true;
        }
        return false;
    }
    
    @SuppressWarnings("rawtypes")
    @Override
    public int getCollectionSize(AtlasSession session, Field field) throws AtlasException {
        Object sourceObject = session.getInput();
        if(field.getDocId() != null) {
            sourceObject = session.getInput(field.getDocId());
        }

        Object collectionObject = ClassHelper.parentObjectForPath(sourceObject, new PathUtil(field.getPath()), false);
        if (collectionObject == null) {
            throw new AtlasException("Cannot find collection on sourceObject '" + sourceObject.getClass().getName() + "' for path: " + field.getPath());
        }
        if (collectionObject.getClass().isArray()) {
            return Array.getLength(collectionObject);
        }
        return ((List)collectionObject).size();
    }
    
    @Override
    public Field cloneField(Field field) throws AtlasException {
        return AtlasJavaModelFactory.cloneJavaField((JavaField)field);
    }
}
