/*
 * Copyright 2020-2022 JetBrains s.r.o. and Kotlin Deep Learning project contributors. All Rights Reserved.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package org.jetbrains.kotlinx.dl.api.core.layer.convolutional

import org.jetbrains.kotlinx.dl.api.core.KGraph
import org.jetbrains.kotlinx.dl.api.core.activation.Activations
import org.jetbrains.kotlinx.dl.api.core.initializer.HeNormal
import org.jetbrains.kotlinx.dl.api.core.initializer.HeUniform
import org.jetbrains.kotlinx.dl.api.core.initializer.Initializer
import org.jetbrains.kotlinx.dl.api.core.layer.*
import org.jetbrains.kotlinx.dl.api.core.regularizer.Regularizer
import org.jetbrains.kotlinx.dl.api.core.shape.TensorShape
import org.jetbrains.kotlinx.dl.api.core.shape.convOutputLength
import org.jetbrains.kotlinx.dl.api.core.shape.numElements
import org.jetbrains.kotlinx.dl.api.core.shape.shapeFromDims
import org.jetbrains.kotlinx.dl.api.core.util.separableConv2dBiasVarName
import org.jetbrains.kotlinx.dl.api.core.util.separableConv2dDepthwiseKernelVarName
import org.jetbrains.kotlinx.dl.api.core.util.separableConv2dPointwiseKernelVarName
import org.tensorflow.Operand
import org.tensorflow.Shape
import org.tensorflow.op.Ops
import org.tensorflow.op.nn.Conv2d
import org.tensorflow.op.nn.DepthwiseConv2dNative
import org.tensorflow.op.nn.DepthwiseConv2dNative.dilations
import kotlin.math.roundToInt

/**
 * 2-D convolution with separable filters.
 *
 * Performs a depthwise convolution that acts separately on channels followed by
 * a pointwise convolution that mixes channels.  Note that this is separability
 * between dimensions `[1, 2]` and `3`, not spatial separability between dimensions `1` and `2`.
 *
 * Intuitively, separable convolutions can be understood as
 * a way to factorize a convolution kernel into two smaller kernels, or as an extreme version of an Inception block.
 *
 * @property [filters] The dimensionality of the output space (i.e. the number of filters in the convolution).
 * @property [kernelSize] Two long numbers, specifying the height and width of the 2D convolution window.
 * @property [strides] Strides of the pooling operation for each dimension of input tensor.
 * NOTE: Specifying any stride value != 1 is incompatible with specifying any `dilation_rate` value != 1.
 * @property [dilations] Four numbers, specifying the dilation rate to use for dilated convolution for each dimension of input tensor.
 * @property [activation] Activation function.
 * @property [depthMultiplier] The number of depthwise convolution output channels for each input channel.
 * The total number of depthwise convolution output channels will be equal to `numberOfChannels * depthMultiplier`.
 * @property [depthwiseInitializer] An initializer for the depthwise kernel matrix.
 * @property [pointwiseInitializer] An initializer for the pointwise kernel matrix.
 * @property [biasInitializer] An initializer for the bias vector.
 * @property [depthwiseRegularizer] Regularizer function applied to the `depthwise` kernel matrix.
 * @property [pointwiseRegularizer] Regularizer function applied to the `pointwise` kernel matrix
 * @property [biasRegularizer] Regularizer function applied to the `bias` vector.
 * @property [activityRegularizer] Regularizer function applied to the output of the layer (its "activation").
 * @property [padding] The padding method, either 'valid' or 'same' or 'full'.
 * @property [useBias] If true the layer uses a bias vector.
 * @property [name] Custom layer name.
 * @constructor Creates [SeparableConv2D] object.
 *
 * @since 0.2
 */
public class SeparableConv2D(
    public val filters: Int = 32,
    public val kernelSize: IntArray = intArrayOf(3, 3),
    public val strides: IntArray = intArrayOf(1, 1, 1, 1),
    public val dilations: IntArray = intArrayOf(1, 1, 1, 1),
    public val activation: Activations = Activations.Relu,
    public val depthMultiplier: Int = 1,
    public val depthwiseInitializer: Initializer = HeNormal(),
    public val pointwiseInitializer: Initializer = HeNormal(),
    public val biasInitializer: Initializer = HeUniform(),
    public val depthwiseRegularizer: Regularizer? = null,
    public val pointwiseRegularizer: Regularizer? = null,
    public val biasRegularizer: Regularizer? = null,
    public val activityRegularizer: Regularizer? = null,
    public val padding: ConvPadding = ConvPadding.SAME,
    public val useBias: Boolean = true,
    name: String = ""
) : Layer(name), NoGradients {
    public constructor(
        filters: Int = 32,
        kernelSize: Int = 3,
        strides: Int = 1,
        dilations: Int = 1,
        activation: Activations = Activations.Relu,
        depthMultiplier: Int = 1,
        depthwiseInitializer: Initializer = HeNormal(),
        pointwiseInitializer: Initializer = HeNormal(),
        biasInitializer: Initializer = HeUniform(),
        depthwiseRegularizer: Regularizer? = null,
        pointwiseRegularizer: Regularizer? = null,
        biasRegularizer: Regularizer? = null,
        activityRegularizer: Regularizer? = null,
        padding: ConvPadding = ConvPadding.SAME,
        useBias: Boolean = true,
        name: String = ""
    ) : this(
        filters = filters,
        kernelSize = intArrayOf(kernelSize, kernelSize),
        strides = intArrayOf(1, strides, strides, 1),
        dilations = intArrayOf(1, dilations, dilations, 1),
        activation = activation,
        depthMultiplier = depthMultiplier,
        depthwiseInitializer = depthwiseInitializer,
        pointwiseInitializer = pointwiseInitializer,
        biasInitializer = biasInitializer,
        depthwiseRegularizer = depthwiseRegularizer,
        pointwiseRegularizer = pointwiseRegularizer,
        biasRegularizer = biasRegularizer,
        activityRegularizer = activityRegularizer,
        padding = padding,
        useBias = useBias,
        name = name
    )

    // weight tensors
    private lateinit var depthwiseKernel: KVariable
    private lateinit var pointwiseKernel: KVariable
    private var bias: KVariable? = null

    init {
        requireArraySize(kernelSize, 2, "kernelSize")
        requireArraySize(strides, 4, "strides")
        requireArraySize(dilations, 4, "dilations")
        isTrainable = false
    }

    override fun build(tf: Ops, kGraph: KGraph, inputShape: Shape) {
        // Amount of channels should be the last value in the inputShape (make warning here)
        val numberOfChannels = inputShape.size(inputShape.numDimensions() - 1)

        // calculate fanIn, fanOut
        val inputDepth = numberOfChannels // amount of channels
        val outputDepth = numberOfChannels * this.depthMultiplier // amount of channels for the next layer

        val fanIn = (inputDepth * kernelSize[0] * kernelSize[1]).toInt()
        val fanOut = ((outputDepth * kernelSize[0] * kernelSize[1] / (strides[0].toDouble() * strides[1])).roundToInt())

        val depthwiseKernelShape = shapeFromDims(*kernelSize.toLongArray(), numberOfChannels, depthMultiplier.toLong())
        depthwiseKernel = createVariable(
            tf,
            kGraph,
            separableConv2dDepthwiseKernelVarName(name),
            isTrainable,
            depthwiseKernelShape,
            fanIn,
            fanOut,
            depthwiseInitializer,
            depthwiseRegularizer
        )
        val pointwiseKernelShape = shapeFromDims(1, 1, numberOfChannels * depthMultiplier, filters.toLong())
        pointwiseKernel = createVariable(
            tf,
            kGraph,
            separableConv2dPointwiseKernelVarName(name),
            isTrainable,
            pointwiseKernelShape,
            fanIn,
            fanOut,
            pointwiseInitializer,
            pointwiseRegularizer
        )
        if (useBias) {
            val biasShape = Shape.make(filters.toLong())
            bias = createVariable(
                tf,
                kGraph,
                separableConv2dBiasVarName(name),
                isTrainable,
                biasShape,
                fanIn,
                fanOut,
                biasInitializer,
                biasRegularizer
            )
        }
    }

    override fun computeOutputShape(inputShape: Shape): Shape {
        var rows = inputShape.size(1)
        var cols = inputShape.size(2)
        rows = convOutputLength(
            rows, kernelSize[0], padding,
            strides[1], dilations[1]
        )
        cols = convOutputLength(
            cols, kernelSize[1], padding,
            strides[2], dilations[2]
        )

        val shape = Shape.make(inputShape.size(0), rows, cols, filters.toLong())
        outputShape = TensorShape(shape)
        return shape
    }

    override fun forward(
        tf: Ops,
        input: Operand<Float>,
        isTraining: Operand<Boolean>,
        numberOfLosses: Operand<Float>?
    ): Operand<Float> {
        val paddingName = padding.paddingName
        val depthwiseConv2DOptions: DepthwiseConv2dNative.Options = dilations(dilations.toLongList()).dataFormat("NHWC")

        val depthwiseOutput: Operand<Float> =
            tf.nn.depthwiseConv2dNative(
                input,
                depthwiseKernel.variable,
                strides.toLongList(),
                paddingName,
                depthwiseConv2DOptions
            )

        val pointwiseStrides = mutableListOf(1L, 1L, 1L, 1L)

        val conv2DOptions: Conv2d.Options = Conv2d.dataFormat("NHWC")
        var output: Operand<Float> =
            tf.nn.conv2d(depthwiseOutput, pointwiseKernel.variable, pointwiseStrides, "VALID", conv2DOptions)

        bias?.let {
            output = tf.nn.biasAdd(output, it.variable)
        }

        return Activations.convert(activation).apply(tf, output, name)
    }

    override fun toString(): String {
        return "SeparableConv2D(name = $name, isTrainable=$isTrainable, filters=$filters, kernelSize=${kernelSize.contentToString()}, strides=${strides.contentToString()}, dilations=${dilations.contentToString()}, activation=$activation, depthMultiplier=$depthMultiplier, depthwiseInitializer=$depthwiseInitializer, pointwiseInitializer=$pointwiseInitializer, biasInitializer=$biasInitializer, depthwiseRegularizer=$depthwiseRegularizer, pointwiseRegularizer=$pointwiseRegularizer, biasRegularizer=$biasRegularizer, activityRegularizer=$activityRegularizer, padding=$padding, useBias=$useBias, depthwiseShapeArray=${depthwiseShapeArray.contentToString()}, pointwiseShapeArray=${pointwiseShapeArray.contentToString()}, biasShapeArray=${biasShapeArray?.contentToString()}, hasActivation=$hasActivation)"
    }

    override var weights: Map<String, Array<*>>
        get() = extractWeights(depthwiseKernel, pointwiseKernel, bias)
        set(value) = assignWeights(value)

    /** Returns the shape of kernel weights. */
    public val depthwiseShapeArray: LongArray? get() = if (this::depthwiseKernel.isInitialized) TensorShape(depthwiseKernel.shape).dims() else null

    /** Returns the shape of kernel weights. */
    public val pointwiseShapeArray: LongArray? get() = if (this::pointwiseKernel.isInitialized) TensorShape(pointwiseKernel.shape).dims() else null

    /** Returns the shape of bias weights. */
    public val biasShapeArray: LongArray? get() = bias?.let { TensorShape(it.shape).dims() }

    override val hasActivation: Boolean get() = true

    override val paramCount: Int
        get() = listOfNotNull(depthwiseKernel, pointwiseKernel, bias).sumOf { it.shape.numElements() }.toInt()


}
