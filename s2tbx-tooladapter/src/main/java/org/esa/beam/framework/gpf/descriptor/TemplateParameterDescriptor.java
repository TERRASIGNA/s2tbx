package org.esa.beam.framework.gpf.descriptor;

import org.esa.beam.framework.gpf.operators.tooladapter.ToolAdapterConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Ramona Manda
 */
public class TemplateParameterDescriptor extends ToolParameterDescriptor{
    private List<ToolParameterDescriptor> toolParameterDescriptors;

    public TemplateParameterDescriptor(String name, Class<?> type, String parameterType){
        super(name, type);
        super.setParameterType(parameterType);
        this.setParameterType(ToolAdapterConstants.TEMPLATE_PARAM_MASK);
        this.toolParameterDescriptors = new ArrayList<>();
    }

    public TemplateParameterDescriptor(DefaultParameterDescriptor object, String parameterType) {
        super(object, parameterType);
        this.toolParameterDescriptors = new ArrayList<>();
    }

    public TemplateParameterDescriptor(ToolParameterDescriptor object) {
        super(object, object.getParameterType());
        this.toolParameterDescriptors = new ArrayList<>();
    }

    public void addParameterDescriptor(ToolParameterDescriptor descriptor){
        this.toolParameterDescriptors.add(descriptor);
    }

    public void removeParameterDescriptor(ToolParameterDescriptor descriptor){
        this.toolParameterDescriptors.remove(descriptor);
    }

    public List<ToolParameterDescriptor> getToolParameterDescriptors(){
        return this.toolParameterDescriptors;
    }

}
