package com.camnter.annotation.processor.compiler.simple.annotation;

import com.camnter.annotation.processor.annotation.SaveView;
import com.camnter.annotation.processor.compiler.simple.ValueIllegalArgumentException;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * @author CaMnter
 */

public class SaveViewField {

    private final int resId;
    private final VariableElement variableElement;


    public SaveViewField(Element element) {
        if (element.getKind() != ElementKind.FIELD) {
            throw new ValueIllegalArgumentException(SaveView.class);
        }
        this.variableElement = (VariableElement) element;
        SaveView saveView = this.variableElement.getAnnotation(SaveView.class);
        this.resId = saveView.value();

        if (this.resId < 0) {
            throw new ValueIllegalArgumentException(SaveView.class, this.variableElement);
        }
    }


    TypeMirror getFieldType() {
        return this.variableElement.asType();
    }


    int getResId() {
        return this.resId;
    }


    Name getFieldName() {
        return this.variableElement.getSimpleName();
    }


    public VariableElement getVariableElement() {
        return this.variableElement;
    }

}