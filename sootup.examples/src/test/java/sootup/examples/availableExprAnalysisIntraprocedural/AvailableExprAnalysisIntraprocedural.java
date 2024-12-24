package sootup.examples.availableExprAnalysisIntraprocedural;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.jdt.core.util.IConstantValueAttribute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sootup.analysis.interprocedural.icfg.AbstractJimpleBasedICFG;
import sootup.core.graph.StmtGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.expr.AbstractBinopExpr;
import sootup.core.jimple.common.expr.AbstractConditionExpr;
import sootup.core.jimple.common.expr.Expr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.core.views.View;
import sootup.java.bytecode.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;

@Tag("Java8")
public class  AvailableExprAnalysisIntraprocedural {
    private static Map<Stmt, Set<Expr>> performAvailableExpressionAnalysis(StmtGraph cfg) {
        Map<Stmt, Set<Expr>> in = new HashMap<>();
        Map<Stmt, Set<Expr>> out = new HashMap<>();
        List<Stmt> stmts = cfg.getStmts();
        for (Stmt stmt : stmts) {
            in.put(stmt, new HashSet<>());
            out.put(stmt, new HashSet<>());
        }

        boolean changed;
        do {
            changed = false;
            for (Stmt stmt : stmts) {
                Set<Expr> inOld = new HashSet<>(in.get(stmt));
                Set<Expr> outOld = new HashSet<>(out.get(stmt));
                // In[stmt] = Intersection of Out[pred] for all predecessors pred of stmt
                Set<Expr> inNew = new HashSet<>();
                if (!cfg.predecessors(stmt).isEmpty()) {
                    boolean first = true;
                    for (Object pred : cfg.predecessors(stmt)) {
                        Set<Expr> outPred = out.get(pred);
                        if (first) {
                            inNew.addAll(outPred);
                            first = false;
                        } else {
                            inNew.retainAll(outPred);
                        }
                    }
                }
                // Out[stmt] = Gen[stmt] ∪ (In[stmt] - Kill[stmt])
                Set<Expr> outNew = new HashSet<>(inNew);
                outNew.addAll(getGen(stmt));
                outNew.removeAll(getKill(stmt, cfg));

                in.put(stmt, inNew);
                out.put(stmt, outNew);

                if (!inOld.equals(inNew) || !outOld.equals(outNew)) {
                    changed = true;
                }
            }
        } while (changed);
        return out;
    }

    private static Set<Expr> getGen(Stmt stmt) {
        Set<Expr> gen = new HashSet<>();
        if (stmt instanceof JAssignStmt) {
            JAssignStmt assignStmt = (JAssignStmt) stmt;
            if (assignStmt.getRightOp() instanceof AbstractBinopExpr) {
                AbstractBinopExpr binopExpr = (AbstractBinopExpr) assignStmt.getRightOp();
                // Exclude expressions involving constants
                if (!(binopExpr.getOp1() instanceof Local && binopExpr.getOp2() instanceof Local)) {
                    return gen; // Skip if one of the operands is not a local variable
                }
                gen.add(binopExpr);
            }
        }
        return gen;
    }

    private static Set<Expr> getKill(Stmt stmt, StmtGraph cfg) {
        Set<Expr> kill = new HashSet<>();
        if (stmt instanceof JAssignStmt) {
            JAssignStmt assignStmt = (JAssignStmt) stmt;
            Value leftOp = assignStmt.getLeftOp();
            if (leftOp instanceof Local) {
                Local defVar = (Local) leftOp;
                Set<Expr> allExpressions = getAllExpressions(cfg);
                for (Expr expr : allExpressions) {
                    if (expr instanceof AbstractBinopExpr) {
                        AbstractBinopExpr binopExpr = (AbstractBinopExpr) expr;
                        // Add expressions to kill set if they use the defined variable
                        if (binopExpr.getOp1().equals(defVar) || binopExpr.getOp2().equals(defVar)) {
                            kill.add(expr);
                        }
                    }
                }
            }
        }
        return kill;
    }

//    private static boolean isExpressionInvolvingConstants(AbstractBinopExpr expr) {
//        return !(expr.getOp1() instanceof Local && expr.getOp2() instanceof Local);
//    }
//
//    private static Set<Expr> killSet(Local defVar, StmtGraph cfg) {
//        Set<Expr> kill = new HashSet<>();
//        Set<Expr> allExpressions = getAllExpressions(cfg);
//        for (Expr expr : allExpressions) {
//            if (expr instanceof AbstractBinopExpr) {
//                AbstractBinopExpr binopExpr = (AbstractBinopExpr) expr;
//                if (binopExpr.getOp1().equals(defVar) || binopExpr.getOp2().equals(defVar)) {
//                    kill.add(expr);
//                }
//            }
//        }
//        return kill;
//    }

    private static Set<Expr> getAllExpressions(StmtGraph cfg) {
        Set<Expr> allExprs = new HashSet<>();
        for (Object stmt : cfg.getStmts()) {
            if (stmt instanceof JAssignStmt) {
                JAssignStmt assignStmt = (JAssignStmt) stmt;
                if (assignStmt.getRightOp() instanceof Expr) {
                    Expr expr = (Expr) assignStmt.getRightOp();
                    if (expr instanceof AbstractBinopExpr) {
                        allExprs.add(expr);
                    }
                }
            }
        }
        return allExprs;
    }

    private void writeAvailableExprAnalysisToCSV(Map<Stmt, Set<Expr>> availableExprs, List<String> javaSourceLines) throws IOException {
        try (FileWriter writer = new FileWriter("src/test/resources/AvailableExprAnalysisIntraprocedural/CSV/AvailableExpressionAnalysis.csv")) {
            writer.append("Line Number,Statement,Available Expressions\n");
            for (Map.Entry<Stmt, Set<Expr>> entry : availableExprs.entrySet()) {
                Stmt stmt = entry.getKey();
                int lineNum = stmt.getPositionInfo().getStmtPosition().getFirstLine();
                if (lineNum <= 0 || lineNum > javaSourceLines.size()) {
                    continue;
                }
                String stmtString = javaSourceLines.get(lineNum - 1).trim();
                String exprsString = entry.getValue().toString();
                writer.append(String.valueOf(lineNum))
                        .append(',')
                        .append(stmtString)
                        .append(',')
                        .append(exprsString)
                        .append('\n');
            }
        }
    }

    private void generateDotFile(Map<Stmt, Set<Expr>> availableExprs, StmtGraph cfg) throws IOException {
        try (FileWriter writer = new FileWriter("src/test/resources/AvailableExprAnalysisIntraprocedural/dotFile/cfg.dot")) {
            writer.write("digraph CFG {\n");
            writer.write("node [shape=box];\n");
            for (Object obj : cfg.getStmts()) {
                if (obj instanceof Stmt) {
                    Stmt stmt = (Stmt) obj;
                    // Skip the goto statements
                    if (stmt.toString().contains("goto")) {
                        continue;
                    }
                    int lineNum = stmt.getPositionInfo().getStmtPosition().getFirstLine();
                    // Create a node for the current statement
                    String nodeName = "stmt" + lineNum;
                    String stmtText = stmt.toString().replace("\"", "\\\"");  // Escape quotes
                    String exprsString = availableExprs.getOrDefault(stmt, Collections.emptySet())
                            .toString().replaceAll("\\s+", "");
                    writer.write(String.format("%s [label=\"Line %d\\n%s\\nAvailable Expressions: %s\"];\n", nodeName, lineNum, stmtText, exprsString));
                    // Iterate over predecessors to create edges
                    for (Object pred : cfg.predecessors(stmt)) {
                        if (pred instanceof Stmt) {
                            Stmt predStmt = (Stmt) pred;
                            int predLineNum = predStmt.getPositionInfo().getStmtPosition().getFirstLine();
                            String predName = "stmt" + predLineNum;
                            // Avoid self-loops
                            if (!nodeName.equals(predName)) {
                                writer.write(String.format("%s -> %s;\n", predName, nodeName));
                            }
                        }
                    }
                }
            }
            writer.write("}\n");
        }
    }

    @Test
    public void createByteCodeProject() throws IOException, InterruptedException {
        Path pathToBinary = Paths.get("src/test/resources/AvailableExprAnalysisIntraprocedural/binary");
        AnalysisInputLocation inputLocation = PathBasedAnalysisInputLocation.create(pathToBinary, null);
        View view = new JavaView(inputLocation);
        ClassType classType = view.getIdentifierFactory().getClassType("Test1");
        MethodSignature methodSignature = view.getIdentifierFactory()
                .getMethodSignature(classType, "main", "void", Collections.singletonList("java.lang.String[]"));
        assertTrue(view.getClass(classType).isPresent());
        SootClass sootClass = view.getClass(classType).get();
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
        Map<Stmt, Set<Expr>> availableExprs = performAvailableExpressionAnalysis(cfg);
        Path javaSourcePath = Paths.get("src/test/resources/AvailableExprAnalysisIntraprocedural/source/Test1.java");
        List<String> javaSourceLines = Files.readAllLines(javaSourcePath);
        writeAvailableExprAnalysisToCSV(availableExprs, javaSourceLines);
        String dotFilePath = "src/test/resources/AvailableExprAnalysisIntraprocedural/dotFile/cfg.dot";
        String outputPngPath = "src/test/resources/AvailableExprAnalysisIntraprocedural/pngFile/cfg.png";
        generateDotFile(availableExprs, cfg);
        generatePngFromDotFile(dotFilePath, outputPngPath);
    }
    private void generatePngFromDotFile(String dotFilePath, String outputPngPath) throws IOException, InterruptedException {
        // Command to execute the Graphviz dot tool
        ProcessBuilder processBuilder = new ProcessBuilder(
                "dot", "-Tpng", dotFilePath, "-o", outputPngPath
        );
        // Start the process
        Process process = processBuilder.start();
        // Wait for the process to complete
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Error generating PNG file. Ensure Graphviz is installed and accessible from the command line.");
        }
    }
}
