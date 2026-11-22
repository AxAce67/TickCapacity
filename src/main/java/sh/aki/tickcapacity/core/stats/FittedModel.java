package sh.aki.tickcapacity.core.stats;

public interface FittedModel {

    double predict(double x);

    double meanStandardError(double x);

    int degreesOfFreedom();

    double rSquared();

    default double lowerBand(double x, double tCritical) {
        return predict(x) - tCritical * meanStandardError(x);
    }

    default double upperBand(double x, double tCritical) {
        return predict(x) + tCritical * meanStandardError(x);
    }
}
