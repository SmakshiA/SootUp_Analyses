package sootup.examples.ReachingDefinitions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.googlecode.d2j.reader.CFG;
import com.googlecode.dex2jar.ir.stmt.AssignStmt;
import com.sun.org.apache.xpath.internal.operations.Variable;
import fj.Unit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import sootup.analysis.interprocedural.icfg.AbstractJimpleBasedICFG;
import sootup.core.graph.StmtGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.core.views.View;
import sootup.java.bytecode.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;

public class ReachingDefinations {
    private static Map<Stmt, Set<String>> performReachingDefinitionsAnalysis(StmtGraph cfg) {
        Map<Stmt, Set<String>> in = new HashMap<>();
        Map<Stmt, Set<String>> out = new HashMap<>();

        List<Stmt> stmts = cfg.getStmts();
        for (Stmt stmt : stmts) {
            in.put(stmt, new HashSet<>());
            out.put(stmt, new HashSet<>());
        }

        boolean changed;
        do {
            changed = false;
            for (Stmt stmt : stmts) {
                Set<String> inOld = new HashSet<>(in.get(stmt));
                Set<String> outOld = new HashSet<>(out.get(stmt));

                Set<String> inNew = new HashSet<>();
                for (Object pred : cfg.predecessors(stmt)) {
                    inNew.addAll(out.get(pred));
                }

                Set<String> outNew = new HashSet<>(in.get(stmt));
                if (isAssignment(stmt)) {
                    outNew.add(stmt.toString());
                }

                in.put(stmt, inNew);
                out.put(stmt, outNew);

                if (!inOld.equals(inNew) || !outOld.equals(outNew)) {
                    changed = true;
                }
            }
        } while (changed);


        return in;
    }
    // Helper to compute the `gen` set for a statement
    private static Set<String> computeGen(Stmt stmt) {
        Set<String> genSet = new HashSet<>();
        stmt.getDef().ifPresent(def -> genSet.add(def.toString()));
        return genSet;
    }

    // Helper to compute the `kill` set for a statement
    private static Set<String> computeKill(Stmt stmt, List<Stmt> stmts) {
        Set<String> killSet = new HashSet<>();
        stmt.getDef().ifPresent(def -> {
            String definedVar = def.toString();
            for (Stmt otherStmt : stmts) {
                otherStmt.getDef().ifPresent(otherDef -> {
                    if (otherDef.toString().equals(definedVar) && !otherStmt.equals(stmt)) {
                        killSet.add(definedVar);
                    }
                });
            }
        });
        return killSet;
    }



    private static boolean isAssignment(Stmt stmt) {
        // Implement logic to determine if the statement is an assignment
        // This is a placeholder implementation
        return stmt.toString().contains("=");
    }

    private static Set<Stmt> getPredecessors(Stmt stmt, List<Stmt> stmts) {
        Set<Stmt> predecessors = new HashSet<>();

        // Iterate through the list of statements
        for (int i = 0; i < stmts.size(); i++) {
            // Find the index of the current statement
            if (stmts.get(i).equals(stmt)) {
                // If it's the first statement, it has no predecessors
                if (i > 0) {
                    // Add the immediate previous statement as a predecessor
                    predecessors.add(stmts.get(i - 1));
                }
                break; // Once the statement is found, stop iterating
            }
        }

        return predecessors;
    }


    @Test
    public void createByteCodeProject() throws IOException, InterruptedException {
        Path pathToBinary = Paths.get("src/test/resources/ReachingDefinitions/binary");
        AnalysisInputLocation inputLocation = PathBasedAnalysisInputLocation.create(pathToBinary, null);

        View view = new JavaView(inputLocation);

        ClassType classType = view.getIdentifierFactory().getClassType("ReachingDefs");

        MethodSignature methodSignature =
                view.getIdentifierFactory()
                        .getMethodSignature(
                                classType, "main", "void", Arrays.asList("java.lang.String[]"));

        assertTrue(view.getClass(classType).isPresent());

        SootClass sootClass = view.getClass(classType).get();

        view.getMethod(methodSignature);

        assertTrue(sootClass.getMethod(methodSignature.getSubSignature()).isPresent());
        SootMethod sootMethod = sootClass.getMethod(methodSignature.getSubSignature()).get();

        AbstractJimpleBasedICFG icfg = new AbstractJimpleBasedICFG() {
            @Override
            public Collection<SootMethod> getCalleesOfCallAt(Stmt stmt) {
                return null;
            }

            @Override
            public Collection<Stmt> getCallersOf(SootMethod sootMethod) {
                return null;
            }
        };
        StmtGraph cfg = icfg.getOrCreateStmtGraph(sootMethod.getBody());
        Map<Stmt, Set<String>> reachingDefsAtEachStmt = performReachingDefinitionsAnalysis(cfg);
        Map<String,Set<String>> reachingDefs = new HashMap<>();
        for(Map.Entry<Stmt,Set<String>> mp : reachingDefsAtEachStmt.entrySet()){
            String str = mp.getKey().toString();
            reachingDefs.put(str,mp.getValue());
        }
        printReachingDefinitionsAnalysis(reachingDefsAtEachStmt);
        convertToCSV(reachingDefsAtEachStmt);
        generateCFGDotFile(cfg,reachingDefsAtEachStmt,"ReachingDef.dot");
        generatePngUsingDot("ReachingDef.dot","Img.png");

    }

    private static void printReachingDefinitionsAnalysis(Map<Stmt, Set<String>> reachingDefsAtEachStmt) {
        for (Map.Entry<Stmt, Set<String>> entry : reachingDefsAtEachStmt.entrySet()) {
            Stmt stmt = entry.getKey();
            System.out.println(stmt+"-----");
            Set<String> reachingDefs = entry.getValue();

            // Filter out stack variables, method invocations, and other unwanted elements
            String filteredStmt = filterStatement(stmt.toString());
            Set<String> filteredReachingDefs = new HashSet<>();

            for (String def : reachingDefs) {
                String filteredDef = filterStatement(def);
                if (!filteredDef.isEmpty()) {
                    filteredReachingDefs.add(filteredDef);
                }
            }

            if (!filteredStmt.isEmpty() && !filteredReachingDefs.isEmpty()) {
                System.out.println("Statement: " + filteredStmt);
                System.out.println("Reaching Definitions: " + filteredReachingDefs);
                System.out.println();
            }
        }
    }
    private static String filterStatement (String stmt) {
        // Simplify the statement string by removing unnecessary parts
        // Adjust this function as needed based on the format of your statements
        if (stmt.contains("virtualinvoke") || stmt.contains("specialinvoke") ||
                stmt.contains("<") || stmt.contains(">") || stmt.contains("$stack")) {
            return "";
        }
        // Return the filtered statement or reaching definition
        if (stmt.contains(":= @parameter")) {
            return ""; // Exclude lines that assign parameters
        }

        return stmt;

    }



    public static void convertToCSV(Map<Stmt, Set<String>> reachingDefsAtEachStmt) {
        String javaFilePath = "src/test/resources/ReachingDefinitions/source/ReachingDefs.java";
        String csvFilePath = "src/test/resources/ReachingDefinitions/CSV/JavaToCSV.csv";

        try (FileWriter csvWriter = new FileWriter(csvFilePath)) {


            csvWriter.append("Line Number,Statement,Reaching Definitions\n");

            for (Map.Entry<Stmt, Set<String>> entry : reachingDefsAtEachStmt.entrySet()) {
                Stmt stmt = entry.getKey();
                Set<String> reachingDefs = entry.getValue();

                String filteredStmt = filterStatement(stmt.toString());
                Set<String> filteredReachingDefs = new HashSet<>();

                for (String def : reachingDefs) {
                    String filteredDef = filterStatement(def);
                    if (!filteredDef.isEmpty()) {
                        filteredReachingDefs.add(filteredDef);
                    }
                }

                if(!filteredStmt.isEmpty() && !filteredReachingDefs.isEmpty()){
                    String st = stmt.getPositionInfo().toString();
                    csvWriter.append(filter(st));
                }
                csvWriter.append(",");
                if (!filteredStmt.isEmpty() && !filteredReachingDefs.isEmpty()) {
                    csvWriter.append(filteredStmt.replace(",", ";"));
                }// Statement
                csvWriter.append(",");
                if (!filteredStmt.isEmpty() && !filteredReachingDefs.isEmpty()) {
                    csvWriter.append(String.join(" ", filteredReachingDefs));
                }// Reaching definitions
                csvWriter.append("\n");
            }

            System.out.println("Reaching Definitions Analysis has been written to CSV file successfully.");

        } catch (IOException e) {
            e.printStackTrace();

        }

    }

    public static String filter(String s){
        int open = -1,close=-1;
        for(int i=0;i<s.length();i++){
            if(s.charAt(i)=='['){
                open = i;
            }
            else if(s.charAt(i)==':'){
                close = i;
            }
        }
        String str = s.substring(open+1,close);
        return str;
    }
    private static void generateCFGDotFile(StmtGraph cfg, Map<Stmt, Set<String>> liveVars, String outputDotFile) throws IOException {
        StringBuilder dotBuilder = new StringBuilder();
        dotBuilder.append("digraph CFG {\n");
        dotBuilder.append("    node [shape=rectangle];\n");

        for (Object stmt : cfg.getStmts()) {
            if (stmt instanceof Stmt) {
                Stmt statement = (Stmt) stmt;
                String sb = statement.getPositionInfo().toString();
                String linenumber = extracttheLine(sb);



                // Filter out stack variables and virtual invoke statements
                if (containsStackVariable(statement) || isVirtualInvoke(statement)) {
                    continue;
                }

                String stmtLabel = statement.toString().replace("\"", "\\\"");


                Set<String> variables = liveVars.getOrDefault(statement, Collections.emptySet());
                Set<String> filteredVars = new HashSet<>();

                for (String var : variables) {
                    String filteredDef = filterStatement(var);
                    if (!filteredDef.isEmpty()) {
                        filteredVars.add(filteredDef);
                    }
                }

                String liveVarsLabel = String.format("%s\nReaching Defs: [%s]", stmtLabel, String.join(", ", filteredVars));

                dotBuilder.append(String.format("\"%s\" [label=\"Line : %s \n %s\"];\n", stmtLabel,linenumber, liveVarsLabel));

            }
        }

        for (Object stmt : cfg.getStmts()) {
            for (Object succ : cfg.getAllSuccessors((Stmt) stmt)) {
                if (stmt instanceof Stmt && succ instanceof Stmt) {
                    dotBuilder.append(String.format("    \"%s\" -> \"%s\";\n", stmt.toString(), succ.toString()));
                }
            }
        }

        dotBuilder.append("}\n");
        Files.write(Paths.get(outputDotFile), dotBuilder.toString().getBytes());
    }

    private static String extracttheLine(String sb) {
        String regex = "\\[(\\d+):";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher matcher = pattern.matcher(sb);

        if (matcher.find()) {
            return matcher.group(1); // Return the first captured group (the number before ":")
        }
        return "Not Found";
    }

    private static boolean isVariable(String str) {
        return str.matches("[a-zA-Z_$][a-zA-Z\\d_$]*");
    }

    private static boolean containsStackVariable(Stmt stmt) {
        if (stmt.getDef().isPresent()) {
            String defVar = stmt.getDef().get().toString();
            return defVar.contains("$");  // Stack variables usually have $ in their names
        }
        return false;
    }

    private static boolean isVirtualInvoke(Stmt stmt) {
        return stmt.toString().contains("invokevirtual");  // Exclude virtual invoke statements
    }

    private static void generatePngUsingDot(String dotFilePath, String outputPngFilePath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("C:\\Program Files\\Graphviz\\bin\\dot.exe", "-Tpng", "C:\\Users\\Aayushee\\Downloads\\SootUp-develop\\SootUp-develop\\sootup.examples\\ReachingDef.dot", "-o", "C:\\Users\\Aayushee\\Downloads\\SootUp-develop\\SootUp-develop\\sootup.examples\\Img.png");
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);


        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            System.out.println("PNG file generated: " + outputPngFilePath);
        } else {
            System.err.println("Error generating PNG. Exit code: " + exitCode);
        }
    }
}
