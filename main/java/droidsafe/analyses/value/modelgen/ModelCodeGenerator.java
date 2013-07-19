package droidsafe.analyses.value.modelgen;

import japa.parser.ASTHelper;
import japa.parser.JavaParser;
import japa.parser.ast.BlockComment;
import japa.parser.ast.Comment;
import japa.parser.ast.CompilationUnit;
import japa.parser.ast.ImportDeclaration;
import japa.parser.ast.LineComment;
import japa.parser.ast.PackageDeclaration;
import japa.parser.ast.TypeParameter;
import japa.parser.ast.body.BodyDeclaration;
import japa.parser.ast.body.ClassOrInterfaceDeclaration;
import japa.parser.ast.body.ConstructorDeclaration;
import japa.parser.ast.body.FieldDeclaration;
import japa.parser.ast.body.MethodDeclaration;
import japa.parser.ast.body.ModifierSet;
import japa.parser.ast.body.Parameter;
import japa.parser.ast.body.TypeDeclaration;
import japa.parser.ast.body.VariableDeclarator;
import japa.parser.ast.body.VariableDeclaratorId;
import japa.parser.ast.expr.BooleanLiteralExpr;
import japa.parser.ast.expr.Expression;
import japa.parser.ast.expr.FieldAccessExpr;
import japa.parser.ast.expr.IntegerLiteralExpr;
import japa.parser.ast.expr.MethodCallExpr;
import japa.parser.ast.expr.NameExpr;
import japa.parser.ast.expr.NullLiteralExpr;
import japa.parser.ast.expr.ObjectCreationExpr;
import japa.parser.ast.stmt.BlockStmt;
import japa.parser.ast.stmt.ExplicitConstructorInvocationStmt;
import japa.parser.ast.stmt.ExpressionStmt;
import japa.parser.ast.stmt.ReturnStmt;
import japa.parser.ast.stmt.Statement;
import japa.parser.ast.type.ClassOrInterfaceType;
import japa.parser.ast.type.PrimitiveType;
import japa.parser.ast.type.PrimitiveType.Primitive;
import japa.parser.ast.type.ReferenceType;
import japa.parser.ast.type.Type;
import japa.parser.ast.type.VoidType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.ArrayType;
import soot.G;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.options.Options;
import droidsafe.analyses.value.ValueAnalysisModeledObject;
import droidsafe.main.Config;

public class ModelCodeGenerator {

    public static final String MODEL_PACKAGE = "droidsafe.analyses.value.models";
    public static final String MODEL_PACKAGE_PREFIX = MODEL_PACKAGE + ".";
    
    public static final List<String> PRIMITIVE_WRAPPER_CLASS_NAMES = Arrays.asList(new String[]{"Boolean",
                                                                                                "Character",
                                                                                                "Byte",
                                                                                                "Short",
                                                                                                "Integer",
                                                                                                "Long",
                                                                                                "Float",
                                                                                                "Double"});
    public static final List<String> COLLECTION_CLASS_NAMES =
            Arrays.asList(new String[]{"BlockingDeque", "BlockingQueue", "Collection", "Deque", "List",
                                       "NavigableSet", "Queue", "Set", "SortedSet", "AbstractCollection",
                                       "AbstractList", "AbstractQueue", "AbstractSequentialList", "AbstractSet",
                                       "ArrayBlockingQueue", "ArrayDeque", "ArrayList", "AttributeList",
                                       "ConcurrentLinkedQueue", "ConcurrentSkipListSet", "CopyOnWriteArrayList",
                                       "CopyOnWriteArraySet", "DelayQueue", "EnumSet", "HashSet", "LinkedBlockingDeque",
                                       "LinkedBlockingQueue", "LinkedHashSet", "LinkedList", "PriorityBlockingQueue",
                                       "PriorityQueue", "Stack", "SynchronousQueue", "TreeSet", "Vector"});
    
    private Map<PrimitiveType.Primitive, Type> primitiveTypeConversionMap;
    
    private Map<String, Type> classTypeConversionMap;

    private static final Logger logger = LoggerFactory.getLogger(ModelCodeGenerator.class);

    private static final Expression NULL = new NullLiteralExpr();

    private static final Expression ZERO = new IntegerLiteralExpr("0");

    private static final Expression FALSE = new BooleanLiteralExpr(false);
    private static final Comment ORIGINAL_MODEL_COMMENT = new LineComment(" ***** From existing model *****");

    private Set<String> classesAlreadyModeled;

    private Set<String> classesCurrentlyModeled = new HashSet<String>();

    private List<String> classesToBeModeled = new ArrayList<String>();

    private String classToModel;

    private String[] sourceDirs;

    private String modelSourceDir;
    private String[] modelSourceDirs;

    private String unqualifiedClassName;

    private String packageName;
    
    private SortedSet<String> imports;

    private String apacHome;

    private File androidImplJar;
    
    private Set<String> importsProcessed;
    private Map<BodyDeclaration, Comment> methodCodeCommentMap;
    private int nextLine;
    private List<File> generatedModels = new ArrayList<File>();

    public ModelCodeGenerator(String sourcePath) {
        sourceDirs = sourcePath.split(":");
        apacHome = System.getenv("APAC_HOME");
        Reflections reflections = new Reflections(MODEL_PACKAGE);
        Set<Class<? extends ValueAnalysisModeledObject>> modeledClasses = 
                reflections.getSubTypesOf(ValueAnalysisModeledObject.class);
        classesAlreadyModeled = new HashSet<String>();
        for (Class<? extends ValueAnalysisModeledObject> modeledClass: modeledClasses)
            classesAlreadyModeled.add(modeledClass.getName());
        logger.debug("APAC_HOME = {}", apacHome);
        if (apacHome == null) {
          logger.error("Environment variable $APAC_HOME not set!");
          droidsafe.main.Main.exit(1);
        }
        modelSourceDir = constructPath(apacHome, "src", "main", "java");
        modelSourceDirs = new String[]{modelSourceDir};
        androidImplJar = constructFile(apacHome, Config.ANDROID_LIB_DIR_REL, "android-impl.jar");
   }

    private void run(String classToModel, Set<String> fieldsToModel) {
        generate(classToModel, fieldsToModel);
        while (!classesToBeModeled.isEmpty()) {
            classToModel = classesToBeModeled.remove(0);
            generate(classToModel, new HashSet<String>());
        }
        installGeneratedModels();
        logger.info("Done.");
    }

    private void generate(String classToModel, Set<String> fieldsToModel) {
        this.classToModel = classToModel;
        packageName = getQualifier(classToModel);
        unqualifiedClassName = getUnqualifiedName(classToModel);
        imports = new TreeSet<String>();
        imports.add("soot.jimple.spark.pag.AllocNode");
        imports.add("droidsafe.analyses.value.ValueAnalysisModeledObject");
        imports.add("droidsafe.analyses.value.ValueAnalysisModelingSet");
        importsProcessed = new HashSet<String>();
        importsProcessed.add(classToModel);
        primitiveTypeConversionMap = new HashMap<PrimitiveType.Primitive, Type>();
        classTypeConversionMap = new HashMap<String, Type>();
        classesCurrentlyModeled.add(classToModel);
        SootClass sootClass = loadSootClass(fieldsToModel);
        CompilationUnit cu;
        CompilationUnit oldModelCu = null;
        try {
            File sourceFile = getJavaSourceFile(sourceDirs, classToModel);
            cu = parseJavaSource(sourceFile);
            computeMethodCodeMap(cu, sourceFile);
            String modelClass = MODEL_PACKAGE_PREFIX + classToModel;
            if (classesAlreadyModeled.contains(modelClass)) {
                File oldModelFile = getJavaSourceFile(modelSourceDirs, modelClass);
                oldModelCu = parseJavaSource(oldModelFile);
            }
            CompilationUnit model = generateModel(cu, sootClass, fieldsToModel, oldModelCu);
            writeModel(model);
        } catch (Exception e) {
            logger.error("Failed to generate model for " + classToModel, e);
        }
    }

    private SootClass loadSootClass(Set<String> fieldsToModel) {
        G.reset();
        logger.info("Loadinging Soot class " + classToModel + "...");
        String[] args = {classToModel};
        Options.v().parse(args);
        soot.options.Options.v().set_keep_line_number(true);
        soot.options.Options.v().set_whole_program(true);
        // allow for the absence of some classes
        soot.options.Options.v().set_allow_phantom_refs(true);
        // set soot classpath to android-impl.jar
        if (!androidImplJar.exists()) {
            logger.error("android-impl.jar does not exist");
            droidsafe.main.Main.exit(1);
        }
        String cp = androidImplJar.getPath();
        soot.options.Options.v().set_soot_classpath(cp);
        System.setProperty("soot.class.path", cp);
        Scene.v().loadNecessaryClasses();
        SootClass sootClass = Scene.v().getSootClass(classToModel);
        // If no field is specified in the command arguments, model all the non-constant fields.
        if (fieldsToModel.isEmpty()) {
            for (SootField field: sootClass.getFields()) {
                if (!field.isStatic() || !field.isFinal())
                fieldsToModel.add(field.getName());
            }
        }
        // TODO: set up soot so we can deduce subtypes of java.util.Collection
        // Scene.v().loadClass("java.util.Collection", SootClass.SIGNATURES);
        // hierarchy = new Hierarchy();
        return sootClass;
    }

    private CompilationUnit parseJavaSource(File sourceFile) throws Exception {
        logger.info("Parsing Java source " + sourceFile + "...");
        FileInputStream in = null;
        CompilationUnit cu = null;
        in = new FileInputStream(sourceFile);
        cu = JavaParser.parse(in);
        nextLine = 1;
        return cu;
    }

    private File getJavaSourceFile(String[] sourceDirs, String className) throws IOException {
        for (String sourceDir: sourceDirs) {
            File javaFile = getJavaSourceFile(sourceDir, className);
            if (javaFile != null)
                return javaFile;
        }
        throw new IOException("Failed to find Java source file for " + className);

    }
    
    private File getJavaSourceFile(String sourceDir, String className) {
        File javaFile = constructFile(sourceDir, className.replace(".", File.separator) + ".java");
        return (javaFile.exists()) ? javaFile : null;
    }
    
    private void computeMethodCodeMap(CompilationUnit cu, File javaFile) throws IOException {
        methodCodeCommentMap = new HashMap<BodyDeclaration, Comment>();
        BufferedReader reader = null;
        List<TypeDeclaration> types = cu.getTypes();
        reader = new BufferedReader(new FileReader(javaFile));
        nextLine = 1;
        for (TypeDeclaration type : types) {
            if (type instanceof ClassOrInterfaceDeclaration) {
                computeMethodCodeMap((ClassOrInterfaceDeclaration)type, reader);
            }
        }
        if (reader != null) 
            reader.close();
    }
    
    private void computeMethodCodeMap(ClassOrInterfaceDeclaration coi, BufferedReader reader) throws IOException {
        for (BodyDeclaration member: coi.getMembers()) {
            if (member instanceof ConstructorDeclaration) {
                Comment codeComment = getMethodCodeComment(reader, ((ConstructorDeclaration) member).getBlock());
                methodCodeCommentMap.put(member, codeComment);
            } else if (member instanceof MethodDeclaration) {
                BlockStmt body = ((MethodDeclaration) member).getBody();
                if (body != null) {
                    Comment codeComment = getMethodCodeComment(reader, body);
                    methodCodeCommentMap.put(member, codeComment);
                }
            } else if (member instanceof ClassOrInterfaceDeclaration) {
                computeMethodCodeMap((ClassOrInterfaceDeclaration)member, reader);
            }
        }
    }

    private Comment getMethodCodeComment(BufferedReader reader, BlockStmt block) throws IOException {
        List<Statement> stmts = block.getStmts();
        if (stmts == null || stmts.isEmpty())
            return null;
        Statement firstStmt = stmts.get(0);
        Statement lastStmt = stmts.get(stmts.size() - 1);
        Comment leadingComment = firstStmt.getComment();
        int beginLine = (leadingComment != null) ? leadingComment.getBeginLine() : firstStmt.getBeginLine();
        int beginColumn = firstStmt.getBeginColumn();
        int endLine = lastStmt.getEndLine();
        return getMethodCodeComment(reader, beginLine, beginColumn, endLine);
    }

    private Comment getMethodCodeComment(BufferedReader reader, int beginLine, int beginColumn, int endLine) throws IOException {
        List<String> lines = new ArrayList<String>();
        StringBuffer buf = new StringBuffer("\n");
        boolean blockComment = true;
        while (nextLine < beginLine)
            readLine(reader);
        while (nextLine < endLine + 1) {
            String line = readLine(reader);
            lines.add(line);
            if (line.contains("*/")) {
                blockComment = false;
            } else {
                buf.append(line);
                buf.append("\n");
            }
        }
        if (blockComment) {
            for (int i = 0; i < beginColumn - 1; i++) {
                buf.append(' ');
            }
            return new BlockComment(buf.toString());
        } else {
            int size = lines.size();
            LineComment comment = new LineComment(lines.get(size - 1));
            LineComment curComment = comment;
            if (size > 1) {
                for (int i = size - 2; i >= 0; i--) {
                    String line = lines.get(i);
                    LineComment prevComment = new LineComment(" " + line.substring(stripIndex(line, beginColumn)));
                    curComment.setComment(prevComment);
                    curComment = prevComment;
                }
            }
            curComment.setComment(new LineComment(""));
            return comment;
        }
    }
    
    private int stripIndex(String line, int max) {
        max = Math.min(max, line.length());
        for (int i = 0; i < max; i++) {
            if (line.charAt(i) != ' ')
                return i;
        }
        return max;
    }

    private String readLine(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line != null) nextLine++;
        return line;
    }

    private CompilationUnit generateModel(CompilationUnit cu, SootClass sootClass, Set<String> fieldsToModel, 
                                          CompilationUnit oldModelCu) throws Exception {
        logger.info("Generating model for " + classToModel + "...");
        CompilationUnit model = new CompilationUnit();

        String modelPackageName = MODEL_PACKAGE_PREFIX + packageName;
        PackageDeclaration modelPkg = new PackageDeclaration(new NameExpr(modelPackageName));
        model.setPackage(modelPkg);

        if (oldModelCu != null) {
            List<ImportDeclaration> oldImports = oldModelCu.getImports();
            if (oldImports != null) {
                for (ImportDeclaration oldImport: oldImports) {
                    String imp = oldImport.getName().toString();
                    if (!imp.contains("*"))
                        imports.add(imp);
                }
            }
        }
        List<TypeDeclaration> types = cu.getTypes();
        List<TypeDeclaration> oldModelTypes = (oldModelCu == null) ? null : oldModelCu.getTypes();
        for (TypeDeclaration type : types) {
            if (type instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration oldModelType = null;
                if (oldModelTypes != null) {
                    for (TypeDeclaration oldType: oldModelTypes) {
                        if (oldType instanceof ClassOrInterfaceDeclaration && 
                                oldType.getName().equals(type.getName())) {
                            oldModelType = (ClassOrInterfaceDeclaration) oldType;
                            break;
                        }
                    }
                }
                ClassOrInterfaceDeclaration modelCoi = generateClassOrInterface((ClassOrInterfaceDeclaration)type, sootClass, fieldsToModel, oldModelType);
                ASTHelper.addTypeDeclaration(model, modelCoi);
            }
        }
        List<ImportDeclaration> importDecls = new ArrayList<ImportDeclaration>();
        for (String imp: imports) {
            importDecls.add(new ImportDeclaration(new NameExpr(imp), false, false));
        }
        model.setImports(importDecls);
        return model;
    }

    private ClassOrInterfaceDeclaration generateClassOrInterface(ClassOrInterfaceDeclaration coi, 
                                                                 SootClass sootClass,
                                                                 Set<String> fieldsToModel,
                                                                 ClassOrInterfaceDeclaration oldModelCoi) throws Exception {
        int modifiers = coi.getModifiers();
        boolean isInterface = coi.isInterface();
        String name = coi.getName();
        Set<String> coiTypeParamNames = typeParameterNames(coi.getTypeParameters());
        ClassOrInterfaceDeclaration modelCoi = new ClassOrInterfaceDeclaration(modifiers, isInterface, name);
        List<ClassOrInterfaceType> extendsList = null;
        if (!isInterface) {
            extendsList = new ArrayList<ClassOrInterfaceType>();
            extendsList.add(new ClassOrInterfaceType("ValueAnalysisModeledObject"));
        }
        SortedSet<BodyDeclaration> allMembers = new TreeSet<BodyDeclaration>(new MemberComparator());
        modelCoi.setExtends(extendsList);
        Set<String> oldMemberCoiNames = new HashSet<String>();
        if (oldModelCoi != null) {
            for (BodyDeclaration oldModelMember : oldModelCoi.getMembers()) {
                addComment(oldModelMember, ORIGINAL_MODEL_COMMENT);
                allMembers.add(oldModelMember);
                if (oldModelMember instanceof ClassOrInterfaceDeclaration)
                    oldMemberCoiNames.add(((ClassOrInterfaceDeclaration) oldModelMember).getName());
            }
        }
        if (!isInterface) {
            ConstructorDeclaration newConstr = generateConstructor(modelCoi);
            allMembers.add(newConstr);
        }
        for (BodyDeclaration member : coi.getMembers()) {
            BodyDeclaration newMember = null;
            if (member instanceof FieldDeclaration) {
                FieldDeclaration field = (FieldDeclaration) member;
                newMember = generateField(modelCoi, sootClass, field, fieldsToModel);
            } else if (member instanceof ConstructorDeclaration) {
                ConstructorDeclaration constr = (ConstructorDeclaration) member;
                newMember = generateInitMethod(modelCoi, sootClass, constr, coiTypeParamNames, fieldsToModel);
            } else if (member instanceof MethodDeclaration) {
                MethodDeclaration method = (MethodDeclaration) member;
                SootMethod sootMethod = getSootMethod(sootClass, method.getName(), method.getParameters(), method.getTypeParameters(), coiTypeParamNames);
                Comment oldCode = (sootMethod.isConcrete()) ? methodCodeCommentMap.get(method) : null;
                method.setThrows(null);
                newMember = convertMethod(modelCoi, method, sootMethod, oldCode, fieldsToModel);
            } else if (member instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration memberCoi = (ClassOrInterfaceDeclaration) member;
                String memberCoiName = memberCoi.getName();
                if (!oldMemberCoiNames.contains(memberCoiName)) {
                    String memberCoiQName = sootClass.getName() + "$" + memberCoiName;
                    importsProcessed.add(memberCoiQName);
                    SootClass memberSootClass = Scene.v().getSootClass(memberCoiQName);
                    ClassOrInterfaceDeclaration oldMemberCoi = null;
                    HashSet<String> fieldNames = new HashSet<String>();
                    for (SootField sootField: memberSootClass.getFields()) {
                        fieldNames.add(sootField.getName());
                    }
                    newMember = generateClassOrInterface(memberCoi, memberSootClass, fieldNames, oldMemberCoi);
                }
            }
            if (newMember != null)
                allMembers.add(newMember);
        }
        for (BodyDeclaration member: allMembers) {
            ASTHelper.addMember(modelCoi, member);
        }
        return modelCoi;
    }
    
    private Set<String> typeParameterNames(List<TypeParameter> typeParameters) {
        Set<String> typeParamNames = new HashSet<String>();
        if (typeParameters != null) {
            for (TypeParameter typeParam: typeParameters) {
                typeParamNames.add(typeParam.getName());
            }
        }
        return typeParamNames;
    }

    private void addComment(BodyDeclaration member, Comment newComment) {
        Comment comment = member.getComment();
        if (comment == null) {
            member.setComment(newComment);
        } else {
            addComment(comment, newComment);
        }
    }

    private void addComment(Comment comment, Comment newComment) {
        Comment prevComment = comment.getComment();
        if (prevComment == null)
            comment.setComment(newComment);
        else 
            addComment(prevComment, newComment);
    }

    private FieldDeclaration generateField(ClassOrInterfaceDeclaration modelCoi, SootClass sootClass, 
                                           FieldDeclaration field, Set<String> fieldsToModel) {
        List<VariableDeclarator> vars = field.getVariables();
        List<VariableDeclarator> modelVars = new ArrayList<VariableDeclarator>();
        for (VariableDeclarator var: vars) {
            if (fieldsToModel.contains(var.getId().getName()))
                 modelVars.add(var);   
        }
        if (!modelVars.isEmpty()) {
            SootField sootField = sootClass.getFieldByName(modelVars.get(0).getId().getName());
            int modifiers = makePublic(field.getModifiers());
            Type type = field.getType();
            soot.Type sootType = sootField.getType();
            Type modelType = convertType(type, sootType);
            if (modelType != type)
                convertInit(modelVars, (ReferenceType) modelType);
            FieldDeclaration modelField = new FieldDeclaration(modifiers, modelType, modelVars);
            modelField.setJavaDoc(field.getJavaDoc());
            return modelField;
        }
        return null;
    }

    private void convertInit(List<VariableDeclarator> modelVars, ReferenceType modelType) {
        Expression init = initForSetOfValues(modelType);
        for (VariableDeclarator modelVar: modelVars)
            modelVar.setInit(init);
    }

    private Expression initForSetOfValues(ReferenceType modelType) {
        ClassOrInterfaceType coi = (ClassOrInterfaceType) modelType.getType();
        Type argType = coi.getTypeArgs().get(0);
        return makeModelingSetCreationExpr(argType);
    }

    private Type convertType(Type type, soot.Type sootType) {
        if (type instanceof ReferenceType) {
            collectImports(sootType);
            ReferenceType refType = (ReferenceType) type;
            if (refType.getArrayCount() == 0 && refType.getType() instanceof ClassOrInterfaceType) {
                ClassOrInterfaceType coi = (ClassOrInterfaceType)refType.getType();
                String coiName = coi.getName();
                if (coiName.equals("String") || PRIMITIVE_WRAPPER_CLASS_NAMES.contains(coiName)) {
                    return convertStringOrPrimitiveWrapperType(coiName);
                } else if (COLLECTION_CLASS_NAMES.contains(coiName)){
                    // SootClass sootClass = ((RefType)sootType).getSootClass();
                    // if (isSubtypeOf(sootClass, Scene.v().getSootClass("java.util.Collection"))) {
                    List<Type> typeArgs = coi.getTypeArgs();
                    if (typeArgs != null && typeArgs.size() == 1) {
                        Type argType = typeArgs.get(0);
                        if (PRIMITIVE_WRAPPER_CLASS_NAMES.contains(argType.toString()))
                            imports.add(((RefType)sootType).getClassName());
                        return makeSetOfType(type);
                    }
                }
            }
        } else if (type instanceof PrimitiveType) {
            PrimitiveType primType = (PrimitiveType) type;
            return convertPrimitive(primType.getType());
        }
        return type;
    }
    
    
    private Type convertStringOrPrimitiveWrapperType(String clsName) {
        Type type = classTypeConversionMap.get(clsName);
        if (type == null) {
            type = makeSetOfType(clsName);
            classTypeConversionMap.put(clsName, type);
        }
        return type;
    }

    private Type convertPrimitive(Primitive prim) {
        Type type = primitiveTypeConversionMap.get(prim);
        if (type == null) {
            imports.add("droidsafe.analyses.value.models.droidsafe.primitives.ValueAnalysis" + prim);
            type = makeSetOfType("ValueAnalysis" + prim);
            primitiveTypeConversionMap.put(prim, type);
        }
        return type;
    }

    private void collectImports(soot.Type sootType) {
        if (sootType instanceof ArrayType) {
            collectImports(((ArrayType)sootType).baseType);
        } else if (sootType instanceof RefType) {
            String clsName = ((RefType)sootType).getClassName();
            collectImports(clsName);
        }
    }

    private void collectImports(String clsName) {
        if (!importsProcessed.contains(clsName)) {
            String modelClsName = MODEL_PACKAGE_PREFIX + clsName;
            if (!getQualifier(clsName).equals("java.lang")) {
                if (clsName.startsWith("android")) {
                    if (!clsName.contains("$") && 
                            !classesAlreadyModeled.contains(modelClsName) &&
                            !classesCurrentlyModeled.contains(clsName) &&
                            !classesToBeModeled.contains(clsName)) {
                        classesToBeModeled.add(clsName);
                    }
                    imports.add(modelClsName);
                } else {
                    imports.add(clsName);
                }
            }
            importsProcessed.add(clsName);
        }
    }
    
    private ConstructorDeclaration generateConstructor(ClassOrInterfaceDeclaration modelCoi) {
        ConstructorDeclaration modelConstr = new ConstructorDeclaration(Modifier.PUBLIC, modelCoi.getName());
        Parameter parameter = ASTHelper.createParameter(makeReferenceType("AllocNode"), "allocNode");
        modelConstr.setParameters(makeParameterList(parameter));
        Statement stmt = new ExplicitConstructorInvocationStmt(false, null, makeExprList(new NameExpr("allocNode")));
        modelConstr.setBlock(makeBlockStmt(stmt));
        return modelConstr;
    }

    private MethodDeclaration generateInitMethod(ClassOrInterfaceDeclaration modelCoi,
                                                 SootClass sootClass,
                                                 ConstructorDeclaration constr, Set<String> coiTypeParamNames,
                                                 Set<String> fieldsToModel) throws Exception {
        List<Parameter> params = constr.getParameters();
        int modifiers = makePublic(constr.getModifiers());
        MethodDeclaration method = new MethodDeclaration(modifiers, ASTHelper.VOID_TYPE, "_init_", params);
        method.setJavaDoc(constr.getJavaDoc());
        method.setComment(constr.getComment());
        method.setBody(constr.getBlock());
        SootMethod sootMethod = getSootMethod(sootClass, "<init>", params, constr.getTypeParameters(), coiTypeParamNames);
        Comment codeComment = methodCodeCommentMap.get(constr);
        return convertMethod(modelCoi, method, sootMethod, codeComment, fieldsToModel);
    }

    private SootMethod getSootMethod(SootClass sootClass, String name, List<Parameter> parameters, 
                                     List<TypeParameter> typeParameters, 
                                     Set<String> coiTypeParamNames) throws Exception {
        SootMethod sootMethod = null;
        Set<String> typeParamNames = typeParameterNames(typeParameters);
        typeParamNames.addAll(coiTypeParamNames);
        try {
            sootMethod = sootClass.getMethodByName(name);
        } catch (RuntimeException e) {
            int paramCount = (parameters == null) ? 0 : parameters.size();
            for (SootMethod m: sootClass.getMethods()) {
                if (m.getName().equals(name)) {
                    boolean match = true;
                    if (m.getParameterCount() == paramCount) {
                        for (int i = 0; i < paramCount; i++) {
                            Type type = parameters.get(i).getType();
                            soot.Type sootType = m.getParameterType(i);
                            if (!typeMatch(type, sootType, typeParamNames)) {
                                match = false;
                                break;
                            }
                        }
                        if (match)
                            sootMethod = m;
                    }
                }
            }
            if (sootMethod == null) {
                StringBuffer buf = new StringBuffer(name);
                buf.append('(');
                for (int i = 0; i < paramCount; i++) {
                    if (i > 0)
                        buf.append(",");
                    buf.append(parameters.get(i));
                }
                buf.append(')');
                throw new Exception("Failed to find soot method " + buf);
            }
        }
        return sootMethod;
    }

    private boolean typeMatch(Type type, soot.Type sootType, Set<String> typeParamNames) {
        if (type instanceof ReferenceType) {
            ReferenceType refType = (ReferenceType) type;
            int dim = refType.getArrayCount();
            Type baseType = refType.getType();
            if (sootType instanceof ArrayType) {
                ArrayType arrType = (ArrayType) sootType;
                return (dim == arrType.numDimensions && 
                        typeMatch(baseType, arrType.baseType, typeParamNames));
            } else
                return dim == 0 && typeMatch(baseType, sootType, typeParamNames);
        } else if (type instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType coiType = (ClassOrInterfaceType) type;
            if (sootType instanceof RefType) {
                RefType sootRefType = (RefType) sootType;
                String clsName = coiType.getName();
                if (typeParamNames.contains(clsName))
                    clsName = "java.lang.Object";
                String sootClsName = sootRefType.getClassName();
                if (clsName.contains("."))
                    return clsName.equals(sootClsName);
                else
                    return clsName.equals(getUnqualifiedName(sootClsName));
            } else
                return false;
        } else
            return type.toString().equals(sootType.toString());
    }

    private MethodDeclaration convertMethod(ClassOrInterfaceDeclaration modelCoi, MethodDeclaration method, SootMethod sootMethod, 
                                            Comment codeComment, Set<String> fieldsToModel) {
        method.setModifiers(makePublic(method.getModifiers()));
        Type returnType = method.getType();
        soot.Type sootReturnType = sootMethod.getReturnType();
        collectImports(sootReturnType);
        List<Parameter> params = method.getParameters();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                Parameter param = params.get(i);
                Type type = param.getType();
                soot.Type sootType = sootMethod.getParameterType(i);
                Type newType = convertType(type, sootType);
                param.setType(newType);
                if (isSetOfType(newType) && !isSetOfType(type)) {
                    String name = param.getId().getName();
                    if (!name.endsWith("s"))
                        param.setId(new VariableDeclaratorId(name+"s"));
                }
            }
        }
        if (codeComment != null) {
            if (isGetterMethodForModeledField(method, fieldsToModel)) {
                method.setType(convertType(returnType, sootReturnType));
            } else {
                List<Statement> newStmts = new ArrayList<Statement>();
                if (!sootMethod.isStatic()) {
                    Statement invalidateStmt = new ExpressionStmt(new MethodCallExpr(null, "__ds__invalidate"));
                    newStmts.add(invalidateStmt);
                }
                if (!(returnType instanceof VoidType)) {
                    Expression returnExpr = defaultInitValue(returnType);
                    Statement returnStmt = new ReturnStmt(returnExpr);
                    newStmts.add(returnStmt);
                }
                BlockStmt newBody = new BlockStmt(newStmts);
                if (codeComment != null) {
                   newBody.setEndComment(codeComment);
                }
                method.setBody(newBody);
            }
        }
        return method;
    }
    
    private boolean isGetterMethodForModeledField(MethodDeclaration method, Set<String> fieldsToModel) {
        BlockStmt body = method.getBody();
        List<Statement> stmts = body.getStmts();
        if (stmts != null && stmts.size() == 1) {
            Statement stmt = stmts.get(0);
            if (stmt instanceof ReturnStmt) {
                Expression returnExpr = ((ReturnStmt)stmt).getExpr();
                if (returnExpr instanceof FieldAccessExpr) {
                    FieldAccessExpr fldAccess = (FieldAccessExpr) returnExpr;
                    Expression scope = fldAccess.getScope();
                    if (scope.toString().equals("this")) {
                        String field = fldAccess.getField();
                        return fieldsToModel.contains(field);
                    }
                } else if (returnExpr instanceof NameExpr) {
                    String field = ((NameExpr)returnExpr).getName();
                    return fieldsToModel.contains(field);
                }
            }
        }
        return false;
    }

    private int makePublic(int modifiers) {
        modifiers = ModifierSet.removeModifier(modifiers, Modifier.PROTECTED);
        modifiers = ModifierSet.removeModifier(modifiers, Modifier.PRIVATE);
        return ModifierSet.addModifier(modifiers, Modifier.PUBLIC);
    }

    private Expression defaultInitValue(Type type) {
       if (type instanceof ReferenceType)
           return NULL;
       if (type instanceof PrimitiveType) {
           switch (((PrimitiveType) type).getType()) {
               case Boolean: return FALSE;
               default: return ZERO;
           }
       }
       return null;
    }

    private void writeModel(CompilationUnit cu) {
        String modelPackageName = MODEL_PACKAGE_PREFIX + packageName;
        File dir = constructFile("generated", modelPackageName.replace(".", File.separator));
        dir.mkdirs();
        PrintWriter out = null;
        File outFile = new File(dir, unqualifiedClassName + ".java");
        logger.info("Writing model code to " + outFile.getPath() + "...");
        try {
            out = new PrintWriter(outFile);
            out.print(cu.toString());
        } catch (FileNotFoundException e) {
            logger.error("generateCodeForModeledClass failed", e);
            droidsafe.main.Main.exit(1);
        } finally {
            if (out != null)
                out.close();
        }
        generatedModels.add(outFile);
    }
    
    private void installGeneratedModels() {
        String curDir = System.getProperty("user.dir");
        String generatedPath = constructPath(curDir, "generated");
        File generatedDir = new File(generatedPath);
        generatedDir.mkdir();
        String undoScriptPath = constructPath(generatedPath, "undomodelgen");
        File undoScriptFile = new File(undoScriptPath);
        if (undoScriptFile.exists()) {
            File undoScirptBackupFile = new File(undoScriptPath + ".backup");
            undoScriptFile.renameTo(undoScirptBackupFile);
        }
        undoScriptFile.setExecutable(true);
        PrintWriter undoPw = null;
        try {
            undoPw = new PrintWriter(undoScriptFile);
            undoPw.println("#!/bin/bash");
            for (File generatedModel: generatedModels) {
                String modelPath = generatedModel.getAbsolutePath().replace(generatedPath, modelSourceDir);
                File model = new File(modelPath);
                File modelDir = model.getParentFile();
                modelDir.mkdirs();
                String backupModelPath = modelPath+".backup";
                boolean restore = false;
                if (model.exists()) {
                    File backupModel = new File(backupModelPath);
                    if (!model.renameTo(backupModel)) 
                        throw new IOException("Failed to rename the existing model to " + backupModel);
                    restore = true;
                }
                if (!generatedModel.renameTo(model))
                    throw new IOException("Failed to rename the generated model to " + model);
                undoPw.println("/bin/rm " + modelPath);
                if (restore)
                    undoPw.println("mv " + backupModelPath + " " + modelPath);
            }
        } catch (IOException e) {
            logger.error("installGeneratedModels failed", e);
        } finally {
            if (undoPw != null)
                undoPw.close();
        }
    }

    private boolean isSetOfType(Type type) {
        if (type instanceof ReferenceType) {
            type = ((ReferenceType) type).getType();
            return type instanceof ClassOrInterfaceType && ((ClassOrInterfaceType)type).getName().equals("Set");
        }
        return false;
    }

    private ReferenceType makeSetOfType(String className) {
        return makeSetOfType(new ClassOrInterfaceType(className));
    }

    private ReferenceType makeSetOfType(Type type) {
        imports.add("java.util.Set");
        return makeGenericReferenceType("Set", type);
    }

    private static ReferenceType makeReferenceType(String className) {
        return new ReferenceType(new ClassOrInterfaceType(className));
    }
    
    private static ReferenceType makeGenericReferenceType(String genericClassName, Type ... typeArgs) {
        ClassOrInterfaceType genericType = makeGenericType(genericClassName, typeArgs);
        return new ReferenceType(genericType);
    }
    
    private static ClassOrInterfaceType makeGenericType(String genericClassName, Type ... typeArgs) {
        ClassOrInterfaceType genericType = new ClassOrInterfaceType(genericClassName);
        genericType.setTypeArgs(Arrays.asList(typeArgs));
        return genericType;
    }
    
    private static Expression makeModelingSetCreationExpr(Type typeArg) {
        return makeGenericObjectCreationExpr("ValueAnalysisModelingSet", typeArg);
    }

    private static Expression makeGenericObjectCreationExpr(String genericClassName, Type ... typeArgs) {
        ClassOrInterfaceType genericType = makeGenericType(genericClassName, typeArgs);
        return new ObjectCreationExpr(null, genericType, null);
    }

    private static List<Parameter> makeParameterList(Parameter ...parameters) {
        List<Parameter> params = new ArrayList<Parameter>();
        for (Parameter param: parameters)
            params.add(param);
        return params;
    }
    
    private static List<Expression> makeExprList(Expression ...expressions) {
        List<Expression> exprs = new ArrayList<Expression>();
        for (Expression expr: expressions)
            exprs.add(expr);
        return exprs;
    }
    
    private static BlockStmt makeBlockStmt(Statement ...statements) {
        List<Statement> stmts = new ArrayList<Statement>();
        for (Statement stmt: statements)
            stmts.add(stmt);
        BlockStmt block = new BlockStmt(stmts);
        return block;
    }

    private String getUnqualifiedName(String name) {
        int index = name.lastIndexOf('.');
        if (index >= 0)
            name = name.substring(index + 1);
        index = name.lastIndexOf('$');
        if (index >= 0)
            name = name.substring(index + 1);
        return name;
    }

    private String getQualifier(String name) {
        int index = name.lastIndexOf('.');
        return (index < 0) ? "" : name.substring(0, index);
    }

    private File constructFile(String ...comps) {
        String path = constructPath(comps);
        return new File(path);        
    }

    private String constructPath(String ...comps) {
        StringBuffer buf = new StringBuffer();
        for (int i = 0; i < comps.length; i++) {
            if (i > 0)
                buf.append(File.separator);
            buf.append(comps[i]);
        }
        return buf.toString();        
    }

    /*
    public boolean isSubtypeOf(SootClass a, SootClass b) {
        if (a.equals(b))
            return true;
        if (b.getType().equals(RefType.v("java.lang.Object")))
            return true;
        if (a.isInterface()) {
            return b.isInterface() && hierarchy.isInterfaceSubinterfaceOf(a, b);
        }
        if (b.isInterface()) {
            return hierarchy.getImplementersOf(b).contains(a);
        }
        return hierarchy.isClassSubclassOf(a, b);
    }
    */

    /**
     * @param args
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            logger.error("Usage: ModelCodeGen <source path> <class name> <field1 name> <field2 name> ...");
            droidsafe.main.Main.exit(1);
        } else {
            String sourcePath = args[0];
            String className = args[1];
            Set<String> fieldNames = new HashSet<String>();
            for (int i = 2; i < args.length; i++) {
                fieldNames.add(args[i]);
            }
            ModelCodeGenerator modelGen = new ModelCodeGenerator(sourcePath);
            modelGen.run(className, fieldNames);
        }
    }

}
