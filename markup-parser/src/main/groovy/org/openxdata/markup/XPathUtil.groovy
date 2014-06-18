package org.openxdata.markup

import org.antlr.runtime.tree.CommonTree
import org.antlr.runtime.tree.Tree
import org.openxdata.xpath.XPathParser

/**
 * Created by kay on 6/16/14.
 */
class XPathUtil {

    String xpath
    CommonTree tree

    XPathUtil(String xpath) {
        this.xpath = xpath
    }

    private CommonTree parse() {
        def parser = Util.createXpathParser(xpath)
        tree = parser.eval().tree as CommonTree
        return tree
    }

    String shorten(Form form) {
        findResults {}
    }

    List<CommonTree> findResults(Closure filter) {
        tree.findResults(filter)
    }

    List<CommonTree> findAll(Closure filter) {
        tree.findAll(filter)
    }

    static {
        CommonTree.metaClass.findResults = { Closure clos ->
            XPathUtil.findResultsImpl(delegate, clos)
        }

        CommonTree.metaClass.findAll = { Closure clos ->
            XPathUtil.findAllImpl(delegate, clos)
        }

        Tree.metaClass.emitTailString{
            XPathUtil.emitTailString(delegate)
        }

        Tree.metaClass.isCommonTree {
            delegate instanceof CommonTree
        }

        CommonTree.metaClass.isPath {
            delegate.token.type == XPathParser.ABSPATH || delegate.token.type == XPathParser.RELPATH
        }
    }

    static List<CommonTree> findResultsImpl(Tree tree, Closure filter) {
        List<CommonTree> trees = []
        int count = tree.getChildCount()
        for (int i = 0; i < count; i++) {
            Tree child = tree.getChild(i)
            def result = filter(child)
            if (result) {
                trees << result
            } else {
                trees.addAll(findResultsImpl(child, filter))
            }
        }
        return trees
    }

    static List<CommonTree> findAllImpl(Tree tree, Closure filter) {
        if (!(tree instanceof CommonTree)) return []
        List<CommonTree> trees = []
        int count = tree.getChildCount()
        for (int i = 0; i < count; i++) {
            Tree child = tree.getChild(i)
            def result = filter(child)
            if (result) {
                trees << child
            } else {
                trees.addAll(findAllImpl(child, filter))
            }
        }
        return trees
    }

    /** Print out only the tails */
    public static String emitTailString(Tree tree) {
        if (tree.getChildCount() == 0) {
            return tree.toString();
        }
        StringBuffer buf = new StringBuffer();

        for (int i = 0; i < tree.getChildCount(); i++) {
            Tree t = (Tree) tree.getChild(i);
            buf.append(emitTailString(t));
        }
        return buf.toString();
    }
}
