/*
 * Copyright 2020-2022 JetBrains s.r.o. and Kotlin Deep Learning project contributors. All Rights Reserved.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package examples.dataset

import org.jetbrains.kotlinx.dl.api.core.Sequential
import org.jetbrains.kotlinx.dl.api.core.WritingMode
import org.jetbrains.kotlinx.dl.api.core.activation.Activations
import org.jetbrains.kotlinx.dl.api.core.initializer.HeNormal
import org.jetbrains.kotlinx.dl.api.core.initializer.HeUniform
import org.jetbrains.kotlinx.dl.api.core.layer.convolutional.Conv2D
import org.jetbrains.kotlinx.dl.api.core.layer.convolutional.ConvPadding
import org.jetbrains.kotlinx.dl.api.core.layer.core.Dense
import org.jetbrains.kotlinx.dl.api.core.layer.core.Input
import org.jetbrains.kotlinx.dl.api.core.layer.pooling.MaxPool2D
import org.jetbrains.kotlinx.dl.api.core.layer.reshaping.Flatten
import org.jetbrains.kotlinx.dl.api.core.loss.Losses
import org.jetbrains.kotlinx.dl.api.core.metric.Metrics
import org.jetbrains.kotlinx.dl.api.core.optimizer.Adam
import org.jetbrains.kotlinx.dl.api.core.summary.logSummary
import org.jetbrains.kotlinx.dl.dataset.OnFlyImageDataset
import org.jetbrains.kotlinx.dl.dataset.cifar10Paths
import org.jetbrains.kotlinx.dl.dataset.handler.extractCifar10LabelsAnsSort
import org.jetbrains.kotlinx.dl.dataset.image.ColorMode
import org.jetbrains.kotlinx.dl.dataset.preprocessor.*
import org.jetbrains.kotlinx.dl.dataset.preprocessor.image.*
import java.io.File

private const val PATH_TO_MODEL = "savedmodels/vgg11"
private const val EPOCHS = 1
private const val TRAINING_BATCH_SIZE = 500
private const val TEST_BATCH_SIZE = 500
private const val NUM_LABELS = 10
private const val NUM_CHANNELS = 3L
private const val IMAGE_SIZE = 60L
private const val TRAIN_TEST_SPLIT_RATIO = 0.8

/**
 * This model is an implementation of VGG'11 model with reduced number of neurons in each layer (2 times for Conv2D and 8 times for Dense).
 *
 * @see <a href="https://drive.google.com/drive/folders/1AgXUyxNj_THugDNZfYvQlPJfR7SKkHW4">
 *     Cifar'10 images and labels could be downloaded here.</a>
 *
 * @see <a href="https://arxiv.org/abs/1409.1556">
 *     Very Deep Convolutional Networks for Large-Scale Image Recognition:[Karen Simonyan, Andrew Zisserman, 2015]</a>
 */
private val vgg11 = Sequential.of(
    Input(
        IMAGE_SIZE,
        IMAGE_SIZE,
        NUM_CHANNELS
    ),
    Conv2D(
        filters = 32,
        kernelSize = intArrayOf(3, 3),
        strides = intArrayOf(1, 1, 1, 1),
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeNormal(),
        padding = ConvPadding.SAME
    ),
    MaxPool2D(
        poolSize = intArrayOf(1, 2, 2, 1),
        strides = intArrayOf(1, 2, 2, 1)
    ),
    Conv2D(
        filters = 64,
        kernelSize = intArrayOf(3, 3),
        strides = intArrayOf(1, 1, 1, 1),
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeNormal(),
        padding = ConvPadding.SAME
    ),
    MaxPool2D(
        poolSize = intArrayOf(1, 2, 2, 1),
        strides = intArrayOf(1, 2, 2, 1)
    ),
    Conv2D(
        filters = 128,
        kernelSize = intArrayOf(3, 3),
        strides = intArrayOf(1, 1, 1, 1),
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeNormal(),
        padding = ConvPadding.SAME
    ),
    Conv2D(
        filters = 128,
        kernelSize = intArrayOf(3, 3),
        strides = intArrayOf(1, 1, 1, 1),
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeNormal(),
        padding = ConvPadding.SAME
    ),
    MaxPool2D(
        poolSize = intArrayOf(1, 2, 2, 1),
        strides = intArrayOf(1, 2, 2, 1)
    ),
    Conv2D(
        filters = 256,
        kernelSize = intArrayOf(3, 3),
        strides = intArrayOf(1, 1, 1, 1),
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeNormal(),
        padding = ConvPadding.SAME
    ),
    Conv2D(
        filters = 256,
        kernelSize = intArrayOf(3, 3),
        strides = intArrayOf(1, 1, 1, 1),
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeNormal(),
        padding = ConvPadding.SAME
    ),
    MaxPool2D(
        poolSize = intArrayOf(1, 2, 2, 1),
        strides = intArrayOf(1, 2, 2, 1)
    ),
    Conv2D(
        filters = 256,
        kernelSize = intArrayOf(3, 3),
        strides = intArrayOf(1, 1, 1, 1),
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeNormal(),
        padding = ConvPadding.SAME
    ),
    Conv2D(
        filters = 256,
        kernelSize = intArrayOf(3, 3),
        strides = intArrayOf(1, 1, 1, 1),
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeNormal(),
        padding = ConvPadding.SAME
    ),
    MaxPool2D(
        poolSize = intArrayOf(1, 2, 2, 1),
        strides = intArrayOf(1, 2, 2, 1)
    ),
    Flatten(),
    Dense(
        outputSize = 512,
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeUniform()
    ),
    Dense(
        outputSize = 512,
        activation = Activations.Relu,
        kernelInitializer = HeNormal(),
        biasInitializer = HeUniform()
    ),
    Dense(
        outputSize = NUM_LABELS,
        activation = Activations.Linear,
        kernelInitializer = HeNormal(),
        biasInitializer = HeUniform()
    )
)

/**
 * This example shows how to do image classification from scratch using [vgg11] model, without leveraging pre-trained weights.
 * We demonstrate the workflow on the Cifar'10 classification dataset.
 *
 * We use the [Preprocessing] DSL to describe the dataset generation pipeline.
 *
 * It includes:
 * - dataset loading from S3
 * - preprocessing DSL declaration
 * - [OnFlyImageDataset] dataset creation
 * - dataset splitting
 * - model compilation
 * - model training
 * - model export
 * - model evaluation
 */
fun main() {
    val preprocessing: Preprocessing = preprocess {
        transformImage {
            crop {
                left = 2
                right = 2
                top = 2
                bottom = 2
            }
            rotate {
                degrees = 90f
            }
            resize {
                outputHeight = IMAGE_SIZE.toInt()
                outputWidth = IMAGE_SIZE.toInt()
                interpolation = InterpolationType.NEAREST
            }
            convert { colorMode = ColorMode.BGR }
        }
        transformTensor {
            rescale {
                scalingCoefficient = 255f
            }
        }
    }

    val (cifarImagesArchive, cifarLabelsArchive) = cifar10Paths()
    val y = extractCifar10LabelsAnsSort(cifarLabelsArchive, 10)
    val dataset = OnFlyImageDataset.create(File(cifarImagesArchive), y, preprocessing)

    val (train, test) = dataset.split(TRAIN_TEST_SPLIT_RATIO)

    vgg11.use {
        it.compile(
            optimizer = Adam(),
            loss = Losses.SOFT_MAX_CROSS_ENTROPY_WITH_LOGITS,
            metric = Metrics.ACCURACY
        )

        it.logSummary()

        val start = System.currentTimeMillis()
        it.fit(dataset = train, epochs = EPOCHS, batchSize = TRAINING_BATCH_SIZE)
        println("Training time: ${(System.currentTimeMillis() - start) / 1000f}")

        it.save(File(PATH_TO_MODEL), writingMode = WritingMode.OVERRIDE)

        val accuracy = it.evaluate(dataset = test, batchSize = TEST_BATCH_SIZE).metrics[Metrics.ACCURACY]

        println("Accuracy: $accuracy")
    }
}

